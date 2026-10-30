package com.cpplatform.service;

import com.cpplatform.entity.SubmissionStatus;
import com.cpplatform.service.JudgePersistence.JudgeJob;
import com.cpplatform.service.JudgePersistence.TcData;
import com.cpplatform.service.JudgePersistence.TcOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a submission's code against all test cases inside isolated, resource-limited
 * Docker containers ("Docker-per-submission" judging). One docker run per test case
 * (plus one compile step for compiled languages).
 *
 * Isolation: --network=none, memory/cpu/pids caps, read-only root fs, non-root user
 * inside the runner image, wall-clock `timeout` as a backstop.
 *
 * Transaction model: this method is {@code @Async} but NOT {@code @Transactional}.
 * All database writes go through {@link JudgePersistence}, a separate bean whose
 * methods each open a short transaction. That keeps the (slow) compile/run steps
 * from holding a database transaction — and a pooled connection — open for their
 * whole duration. See {@link JudgePersistence} for why it is a separate bean.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JudgeService {

    private final JudgePersistence persistence;

    @Value("${judge.workspace-dir}")
    private String workspaceDir;

    @Value("${judge.docker-memory}")
    private String dockerMemory;

    @Value("${judge.docker-cpus}")
    private String dockerCpus;

    @Value("${judge.docker-pids-limit}")
    private String dockerPidsLimit;

    private record RunResult(String stdout, String stderr, int exitCode, boolean timedOut, long wallMs) {}

    @Async
    public void judgeAsync(Long submissionId) {
        JudgeJob job;
        try {
            job = persistence.beginJudging(submissionId); // short tx: mark RUNNING, load inputs
        } catch (Exception e) {
            log.error("Failed to start judging submission {}", submissionId, e);
            safeMarkError(submissionId, "Failed to start judging: " + e.getMessage());
            return;
        }

        Path workDir = null;
        try {
            RunnerConfig cfg = RunnerConfig.forLanguage(job.language());

            workDir = Files.createDirectories(Path.of(workspaceDir, "sub-" + submissionId));
            // the sandbox container runs as a non-root user for isolation, but the
            // directory was just created by this process — without this, g++ can read
            // solution.cpp but can't write the compiled binary back into /box
            try {
                Files.setPosixFilePermissions(workDir,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem (shouldn't happen in the Linux container, but don't crash if so)
            }
            Files.writeString(workDir.resolve(cfg.sourceFileName()), job.sourceCode(), StandardCharsets.UTF_8);

            // --- Compile step (no-op for interpreted languages without a compile command) ---
            if (cfg.needsCompile()) {
                RunResult compileResult = runDocker(cfg.image(), workDir,
                        cfg.compileCommand(), 20, /*networkNone*/ true, /*compileStep*/ true);
                if (compileResult.exitCode() != 0) {
                    persistence.recordCompileError(submissionId, truncate(compileResult.stderr(), 4000));
                    return;
                }
            }

            // --- Run each test case (no DB transaction open during any of this) ---
            int passed = 0;
            int maxRuntimeMs = 0;
            SubmissionStatus finalVerdict = SubmissionStatus.ACCEPTED;
            List<TcOutcome> outcomes = new ArrayList<>();
            double timeLimitSec = Math.max(1, job.timeLimitMs() / 1000.0);

            for (TcData tc : job.testCases()) {
                Files.writeString(workDir.resolve("input.txt"), tc.input(), StandardCharsets.UTF_8);

                RunResult run = runDocker(cfg.image(), workDir, cfg.runCommand(),
                        (int) Math.ceil(timeLimitSec) + 2, true, false);

                SubmissionStatus verdict;
                if (run.timedOut()) {
                    verdict = SubmissionStatus.TIME_LIMIT_EXCEEDED;
                } else if (run.exitCode() != 0) {
                    verdict = SubmissionStatus.RUNTIME_ERROR;
                } else if (normalize(run.stdout()).equals(normalize(tc.expectedOutput()))) {
                    verdict = SubmissionStatus.ACCEPTED;
                    passed++;
                } else {
                    verdict = SubmissionStatus.WRONG_ANSWER;
                }

                maxRuntimeMs = Math.max(maxRuntimeMs, (int) run.wallMs());

                if (verdict != SubmissionStatus.ACCEPTED) {
                    log.warn("Submission {} testCase {} verdict={} exitCode={} timedOut={} stderr=[{}] stdout=[{}]",
                            submissionId, tc.id(), verdict, run.exitCode(), run.timedOut(),
                            truncate(run.stderr(), 500), truncate(run.stdout(), 200));
                }

                outcomes.add(new TcOutcome(tc.id(), verdict, (int) run.wallMs(), null));

                if (verdict != SubmissionStatus.ACCEPTED && finalVerdict == SubmissionStatus.ACCEPTED) {
                    finalVerdict = verdict; // first failing verdict becomes the overall verdict
                }
            }

            int score = job.testCases().isEmpty() ? 0
                    : (int) Math.round(100.0 * passed / job.testCases().size());

            persistence.saveResults(submissionId, finalVerdict, score, maxRuntimeMs, outcomes); // short tx

        } catch (Exception e) {
            log.error("Judging failed for submission {}", submissionId, e);
            safeMarkError(submissionId, truncate(e.getMessage(), 2000));
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** Move the submission to a terminal error state without letting a second failure escape. */
    private void safeMarkError(Long submissionId, String message) {
        try {
            persistence.markInternalError(submissionId, message);
        } catch (Exception e) {
            log.error("Also failed to mark submission {} as INTERNAL_ERROR", submissionId, e);
        }
    }

    /**
     * Runs `docker run` with hard isolation flags. compileStep gets a slightly larger
     * timeout and doesn't feed stdin; a normal run step pipes input.txt as stdin.
     */
    private RunResult runDocker(String image, Path workDir, List<String> command,
                                 int timeoutSeconds, boolean networkNone, boolean compileStep) throws IOException, InterruptedException {

        List<String> cmd = new java.util.ArrayList<>(List.of(
                "docker", "run", "--rm",
                "-i",
                networkNone ? "--network=none" : "--network=bridge",
                "--memory=" + dockerMemory,
                "--memory-swap=" + dockerMemory,
                "--cpus=" + dockerCpus,
                "--pids-limit=" + dockerPidsLimit,
                "--read-only",
                "--tmpfs", "/tmp:rw,size=32m",
                "-v", workDir.toAbsolutePath() + ":/box:rw",
                "-w", "/box",
                image
        ));
        cmd.addAll(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // pipe stdin from input.txt for run steps; nothing for compile steps
        if (!compileStep) {
            Path input = workDir.resolve("input.txt");
            if (Files.exists(input)) {
                try (var os = process.getOutputStream()) {
                    Files.copy(input, os);
                }
            } else {
                process.getOutputStream().close();
            }
        } else {
            process.getOutputStream().close();
        }

        long start = System.currentTimeMillis();
        String stdout;
        String stderr;
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long wallMs = System.currentTimeMillis() - start;

        if (!finished) {
            process.destroyForcibly();
            return new RunResult("", "timed out", -1, true, wallMs);
        }

        stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new RunResult(stdout, stderr, process.exitValue(), false, wallMs);
    }

    private String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\r\\n", "\n");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private void deleteRecursively(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
