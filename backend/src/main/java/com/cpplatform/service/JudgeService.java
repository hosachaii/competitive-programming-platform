package com.cpplatform.service;

import com.cpplatform.entity.SubmissionStatus;
import com.cpplatform.service.ContainerRunner.RunResult;
import com.cpplatform.service.JudgePersistence.JudgeJob;
import com.cpplatform.service.JudgePersistence.TcData;
import com.cpplatform.service.JudgePersistence.TcOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a submission's code against all test cases in isolated, resource-limited
 * sandboxes ("Docker-per-submission" judging): one run per test case, plus one
 * compile step for compiled languages. The actual sandbox execution is delegated to
 * a {@link ContainerRunner} (Docker in production, mocked in tests); this class owns
 * the orchestration, verdict decision, and result persistence.
 *
 * Transaction model: this method is {@code @Async} but NOT {@code @Transactional};
 * all DB writes go through the short transactions in {@link JudgePersistence}, so no
 * transaction is held open across the (slow) compile/run steps.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JudgeService {

    private final JudgePersistence persistence;
    private final LeaderboardService leaderboardService;
    private final ContainerRunner containerRunner;

    @Value("${judge.workspace-dir}")
    private String workspaceDir;

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
                RunResult compileResult = containerRunner.run(cfg.image(), workDir,
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

                RunResult run = containerRunner.run(cfg.image(), workDir, cfg.runCommand(),
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

            // short tx; returns the user id only on their first solve of this problem
            Long newlySolvedUserId = persistence.saveResults(submissionId, finalVerdict, score, maxRuntimeMs, outcomes);
            if (newlySolvedUserId != null) {
                leaderboardService.onFirstSolve(newlySolvedUserId); // Redis update, outside any tx
            }

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
