package com.cpplatform.service;

import com.cpplatform.entity.*;
import com.cpplatform.repository.SubmissionRepository;
import com.cpplatform.repository.SubmissionResultRepository;
import com.cpplatform.repository.TestCaseRepository;
import com.cpplatform.repository.UserProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The leaderboard only gets bumped on a user's FIRST accepted solve of a problem.
 * {@link JudgePersistence#saveResults} encodes that by returning the user id only on
 * the first solve (and null afterwards), so a re-solve can't increase the score.
 */
class JudgePersistenceFirstSolveTest {

    private SubmissionRepository submissions;
    private SubmissionResultRepository results;
    private TestCaseRepository testCases;
    private UserProgressRepository progressRepo;
    private JudgePersistence persistence;

    private Submission submission;

    @BeforeEach
    void setUp() {
        submissions = mock(SubmissionRepository.class);
        results = mock(SubmissionResultRepository.class);
        testCases = mock(TestCaseRepository.class);
        progressRepo = mock(UserProgressRepository.class);
        persistence = new JudgePersistence(submissions, results, testCases, progressRepo);

        User user = User.builder().id(7L).email("u@x.com").username("u").passwordHash("h").role(Role.USER).build();
        Problem problem = Problem.builder().id(1L).title("P").slug("p").difficulty(Problem.Difficulty.EASY)
                .statement("s").build();
        submission = Submission.builder().id(100L).user(user).problem(problem)
                .language(Language.CPP).sourceCode("x").status(SubmissionStatus.RUNNING).build();
        when(submissions.findById(100L)).thenReturn(Optional.of(submission));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstAcceptedReturnsUserId_repeatReturnsNull() {
        // First solve: no existing progress row -> created and flipped to solved.
        when(progressRepo.findByUserIdAndProblemId(7L, 1L)).thenReturn(Optional.empty());
        Long firstSolver = persistence.saveResults(100L, SubmissionStatus.ACCEPTED, 100, 10, List.of());
        assertEquals(7L, firstSolver, "first accepted solve should report the user id for a leaderboard bump");

        // Second accepted solve: progress already solved -> no bump.
        UserProgress alreadySolved = UserProgress.builder()
                .user(submission.getUser()).problem(submission.getProblem()).solved(true).build();
        when(progressRepo.findByUserIdAndProblemId(7L, 1L)).thenReturn(Optional.of(alreadySolved));
        Long repeatSolver = persistence.saveResults(100L, SubmissionStatus.ACCEPTED, 100, 10, List.of());
        assertNull(repeatSolver, "re-solving an already-solved problem must not bump the score");
    }

    @Test
    void nonAcceptedVerdictNeverBumps() {
        when(progressRepo.findByUserIdAndProblemId(7L, 1L)).thenReturn(Optional.empty());
        Long solver = persistence.saveResults(100L, SubmissionStatus.WRONG_ANSWER, 0, 10, List.of());
        assertNull(solver);
    }
}
