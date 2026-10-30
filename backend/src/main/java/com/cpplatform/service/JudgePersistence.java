package com.cpplatform.service;

import com.cpplatform.entity.*;
import com.cpplatform.repository.SubmissionRepository;
import com.cpplatform.repository.SubmissionResultRepository;
import com.cpplatform.repository.TestCaseRepository;
import com.cpplatform.repository.UserProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The transactional boundary around judging. Deliberately a bean of its own,
 * separate from {@link JudgeService}: the heavy work (compiling and running code
 * in Docker) must happen with NO database transaction — and therefore no pooled
 * DB connection — held open. Each method here opens a short transaction and
 * returns.
 *
 * It lives in a separate bean rather than as {@code @Transactional} methods on
 * JudgeService because Spring's {@code @Transactional} is applied by a proxy: a
 * call from one method of a bean to another method of the SAME bean bypasses the
 * proxy, so the annotation would be silently ignored (the "self-invocation"
 * gotcha). Calling across to this bean goes through its proxy, so the
 * transactions actually take effect.
 */
@Service
@RequiredArgsConstructor
public class JudgePersistence {

    private final SubmissionRepository submissionRepository;
    private final SubmissionResultRepository submissionResultRepository;
    private final TestCaseRepository testCaseRepository;
    private final UserProgressRepository userProgressRepository;

    /** Everything the judge needs, extracted eagerly so it stays valid after the tx closes. */
    public record JudgeJob(Long submissionId, Language language, String sourceCode,
                           int timeLimitMs, List<TcData> testCases) {}

    public record TcData(Long id, String input, String expectedOutput) {}

    /** Outcome of one test case, to be persisted once judging finishes. */
    public record TcOutcome(Long testCaseId, SubmissionStatus verdict, int runtimeMs, Integer memoryKb) {}

    /**
     * Short transaction: mark the submission RUNNING and read out everything the
     * judge will need (source, time limit, and all test cases) into a detached
     * carrier, so no lazy associations are touched once the transaction closes.
     */
    @Transactional
    public JudgeJob beginJudging(Long submissionId) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow();
        submission.setStatus(SubmissionStatus.RUNNING);
        submissionRepository.save(submission);

        Problem problem = submission.getProblem();
        List<TcData> cases = testCaseRepository.findByProblemId(problem.getId()).stream()
                .map(tc -> new TcData(tc.getId(), tc.getInput(), tc.getExpectedOutput()))
                .toList();

        return new JudgeJob(submission.getId(), submission.getLanguage(), submission.getSourceCode(),
                problem.getTimeLimitMs(), cases);
    }

    /** Short transaction: the code failed to compile. */
    @Transactional
    public void recordCompileError(Long submissionId, String compilerOutput) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow();
        submission.setStatus(SubmissionStatus.COMPILE_ERROR);
        submission.setErrorMessage(compilerOutput);
        submission.setScore(0);
        submission.setJudgedAt(Instant.now());
        submissionRepository.save(submission);
    }

    /**
     * Short transaction: persist every per-test-case result, the overall verdict,
     * score and runtime, and update the user's progress. Called once, after all
     * Docker execution is done.
     *
     * @return the user id if this submission was the user's FIRST solve of the
     *         problem (so the caller can bump the leaderboard outside this
     *         transaction), otherwise {@code null}.
     */
    @Transactional
    public Long saveResults(Long submissionId, SubmissionStatus finalVerdict, int score,
                            int maxRuntimeMs, List<TcOutcome> outcomes) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow();

        for (TcOutcome o : outcomes) {
            TestCase tcRef = testCaseRepository.getReferenceById(o.testCaseId());
            submissionResultRepository.save(SubmissionResult.builder()
                    .submission(submission)
                    .testCase(tcRef)
                    .verdict(o.verdict())
                    .runtimeMs(o.runtimeMs())
                    .memoryKb(o.memoryKb())
                    .build());
        }

        submission.setStatus(finalVerdict);
        submission.setScore(score);
        submission.setRuntimeMs(maxRuntimeMs);
        submission.setJudgedAt(Instant.now());
        submissionRepository.save(submission);

        boolean newlySolved = updateProgress(submission, finalVerdict == SubmissionStatus.ACCEPTED);
        return newlySolved ? submission.getUser().getId() : null;
    }

    /**
     * Short transaction: put the submission into a terminal error state. Used when
     * judging throws unexpectedly, so a submission never gets stuck in RUNNING.
     */
    @Transactional
    public void markInternalError(Long submissionId, String message) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.setStatus(SubmissionStatus.INTERNAL_ERROR);
            submission.setErrorMessage(message);
            submission.setJudgedAt(Instant.now());
            submissionRepository.save(submission);
        });
    }

    /** @return true if this call flipped the problem to solved for the first time. */
    private boolean updateProgress(Submission submission, boolean solved) {
        Long userId = submission.getUser().getId();
        Long problemId = submission.getProblem().getId();
        var progress = userProgressRepository.findByUserIdAndProblemId(userId, problemId)
                .orElseGet(() -> UserProgress.builder()
                        .user(submission.getUser())
                        .problem(submission.getProblem())
                        .build());
        progress.setAttempts(progress.getAttempts() + 1);
        boolean newlySolved = false;
        if (solved && !progress.isSolved()) {
            progress.setSolved(true);
            progress.setBestSubmissionId(submission.getId());
            newlySolved = true;
        }
        userProgressRepository.save(progress);
        return newlySolved;
    }
}
