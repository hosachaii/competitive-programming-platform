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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs a submission's code against all test cases inside isolated, resource-limited
 * Docker containers ("Docker-per-submission" judging). One docker run per test case
 * (plus one compile step for compiled languages).
 *
 * Isolation: --network=none, memory/cpu/pids caps, read-only root fs, non-root user
 * inside the runner image, wall-clock timeout as a backstop.
 *
 * Robustness (Phase 3): every container gets a unique --name so a timed-out or
 * runaway container can be force-removed (no orphans); stdout/stderr are drained
 * concurrently and capped so a flood can't exhaust memory; OOM kills (exit 137) and
 * output floods map to MLE / OLE respectively.
 *
 * Transaction model: this method is {@code @Async} but NOT {@code @Transactional};
 * all DB writes go through the short transactions in {@link JudgePersistence}.
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

    @Value("${judge.max-output-bytes}")
    private int maxOutputBytes;

    /**
     * Result of one container run. {@code outputExceeded} means the program wrote
     * more than the cap (we killed it); {@code outOfMemory} means the kernel OOM-killed
     * it (exit 137 with no wall-clock timeout).
     */
    record RunResult(String stdout, String stderr, int exitCode,
                     boolean timedOut, boolean outOfMemory, boolean outputExceeded, long wallMs) {}

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

            // --- Compile step (C++/Java; Python byte-compile for syntax checking) ---
            if (cfg.needsCompile()) {
                RunResult compileResult = runDocker(cfg.image(), workDir,
                        cfg.compileCommand(), 20, /*compileStep*/ true);
                if (compileResult.exitCode() != 0) {
                    String msg = compileResult.timedOut() ? "Compilation timed out" : compileResult.stderr();
                    persistence.recordCompileError(submissionId, truncate(msg, 4000));
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
                        (int) Math.ceil(timeLimitSec) + 2, false);

                SubmissionStatus verdict = mapVerdict(run, tc.expectedOutput());
                if (verdict == SubmissionStatus.ACCEPTED) passed++;

                maxRuntimeMs = Math.max(maxRuntimeMs, (int) run.wallMs());

                if (verdict != SubmissionStatus.ACCEPTED) {
                    log.warn("Submission {} testCase {} verdict={} exitCode={} timedOut={} oom={} outExceeded={} stderr=[{}]",
                            submissionId, tc.id(), verdict, run.exitCode(), run.timedOut(),
                            run.outOfMemory(), run.outputExceeded(), truncate(run.stderr(), 500));
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
                deleteRecursively(workDir); // per-submission workspace cleanup
            }
        }
    }

    /**
     * Maps a container run to a verdict. Order matters: a wall-clock timeout wins,
     * then an output flood (we killed it), then an OOM kill, then any other non-zero
     * exit is a runtime error; a clean exit is AC or WA by output comparison.
     * Package-private + static so it can be unit-tested directly.
     */
    static SubmissionStatus mapVerdict(RunResult run, String expectedOutput) {
        if (run.timedOut())       return SubmissionStatus.TIME_LIMIT_EXCEEDED;
        if (run.outputExceeded()) return SubmissionStatus.OUTPUT_LIMIT_EXCEEDED;
        if (run.outOfMemory())    return SubmissionStatus.MEMORY_LIMIT_EXCEEDED;
        if (run.exitCode() != 0)  return SubmissionStatus.RUNTIME_ERROR;
        return OutputComparator.matches(run.stdout(), expectedOutput)
                ? SubmissionStatus.ACCEPTED
                : SubmissionStatus.WRONG_ANSWER;
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
     * Runs `docker run` with hard isolation flags under a unique container name.
     * stdout/stderr are drained on their own threads (so a large writer can't
     * deadlock against a full pipe buffer) and capped at maxOutputBytes; if the cap
     * is exceeded the container is killed and the run is flagged OLE. On any forced
     * kill (timeout or flood) the container is force-removed so nothing is orphaned.
     */
    private RunResult runDocker(String image, Path workDir, List<String> command,
                                int timeoutSeconds, boolean compileStep) throws IOException, InterruptedException {

        String containerName = "judge-" + UUID.randomUUID();
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "-i",
                "--network=none",
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

        // Drain both streams concurrently with a hard cap; kill the container if a
        // stream floods past the cap.
        StreamGobbler out = new StreamGobbler(process.getInputStream(), maxOutputBytes,
                () -> forceKill(process, containerName));
        StreamGobbler err = new StreamGobbler(process.getErrorStream(), maxOutputBytes,
                () -> forceKill(process, containerName));
        out.start();
        err.start();

        // Feed stdin (test input) for run steps; compile steps get none.
        if (!compileStep) {
            Path input = workDir.resolve("input.txt");
            if (Files.exists(input)) {
                try (var os = process.getOutputStream()) {
                    Files.copy(input, os);
                } catch (IOException ignored) {
                    // program may have exited/closed stdin before reading it all; fine
                }
            } else {
                process.getOutputStream().close();
            }
        } else {
            process.getOutputStream().close();
        }

        long start = System.currentTimeMillis();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long wallMs = System.currentTimeMillis() - start;

        if (!finished) {
            forceKill(process, containerName);   // kill CLI + force-remove the container
            out.join(2000);
            err.join(2000);
            return new RunResult("", "timed out", -1, true, false, false, wallMs);
        }

        out.join(2000);
        err.join(2000);
        int exitCode = process.exitValue();
        boolean exceeded = out.capExceeded() || err.capExceeded();
        if (exceeded) {
            // we killed the container because it flooded output; make sure it's gone
            forceRemove(containerName);
        }
        boolean oom = !exceeded && exitCode == 137; // 128 + SIGKILL, i.e. cgroup OOM kill

        return new RunResult(out.text(), err.text(), exitCode, false, oom, exceeded, wallMs);
    }

    /** Kill the docker CLI process and force-remove the container it launched. */
    private void forceKill(Process process, String containerName) {
        process.destroyForcibly();
        forceRemove(containerName);
    }

    /** `docker rm -f` the named container; best-effort, so already-gone is fine. */
    private void forceRemove(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
        }
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

    /**
     * Reads a stream to completion, keeping at most {@code cap} bytes. If the stream
     * exceeds the cap it flags {@code capExceeded} and invokes {@code onExceed} once
     * (used to kill the container), then keeps draining so the writer never blocks.
     */
    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final int cap;
        private final Runnable onExceed;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean exceeded = false;

        StreamGobbler(InputStream in, int cap, Runnable onExceed) {
            this.in = in;
            this.cap = cap;
            this.onExceed = onExceed;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    int remaining = cap - buf.size();
                    if (remaining > 0) {
                        buf.write(chunk, 0, Math.min(n, remaining));
                    }
                    if (!exceeded && buf.size() >= cap) {
                        exceeded = true;
                        try { onExceed.run(); } catch (Exception ignored) {}
                    }
                    // keep reading (and discarding beyond the cap) so the writer can't block
                }
            } catch (IOException ignored) {
                // stream closed as the process died; nothing more to read
            }
        }

        boolean capExceeded() { return exceeded; }

        String text() { return buf.toString(StandardCharsets.UTF_8); }
    }
}
