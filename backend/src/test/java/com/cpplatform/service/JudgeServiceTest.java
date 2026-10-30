package com.cpplatform.service;

import com.cpplatform.entity.Language;
import com.cpplatform.entity.SubmissionStatus;
import com.cpplatform.service.ContainerRunner.RunResult;
import com.cpplatform.service.JudgePersistence.JudgeJob;
import com.cpplatform.service.JudgePersistence.TcData;
import com.cpplatform.service.JudgePersistence.TcOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the full {@link JudgeService#judgeAsync} orchestration with the container
 * execution mocked, so AC / WA / TLE / CE flows can be verified without Docker.
 */
class JudgeServiceTest {

    private JudgePersistence persistence;
    private LeaderboardService leaderboard;
    private ContainerRunner runner;
    private JudgeService judge;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        persistence = mock(JudgePersistence.class);
        leaderboard = mock(LeaderboardService.class);
        runner = mock(ContainerRunner.class);
        judge = new JudgeService(persistence, leaderboard, runner);
        ReflectionTestUtils.setField(judge, "workspaceDir", workspace.toString());
    }

    private void jobWithOneCase(String expectedOutput) {
        JudgeJob job = new JudgeJob(1L, Language.CPP, "int main(){}", 2000,
                List.of(new TcData(10L, "in", expectedOutput)));
        when(persistence.beginJudging(1L)).thenReturn(job);
    }

    private RunResult ok(String stdout, int exit) {
        return new RunResult(stdout, "", exit, false, false, false, 5);
    }

    @Test
    void acceptedFlow_savesAcceptedAndBumpsLeaderboard() throws Exception {
        jobWithOneCase("5");
        when(runner.run(any(), any(), any(), anyInt(), eq(true))).thenReturn(ok("", 0));   // compile
        when(runner.run(any(), any(), any(), anyInt(), eq(false))).thenReturn(ok("5\n", 0)); // run
        when(persistence.saveResults(eq(1L), eq(SubmissionStatus.ACCEPTED), eq(100), anyInt(), anyList()))
                .thenReturn(7L); // user 7's first solve

        judge.judgeAsync(1L);

        ArgumentCaptor<List<TcOutcome>> outcomes = ArgumentCaptor.forClass(List.class);
        verify(persistence).saveResults(eq(1L), eq(SubmissionStatus.ACCEPTED), eq(100), anyInt(), outcomes.capture());
        assertEquals(SubmissionStatus.ACCEPTED, outcomes.getValue().get(0).verdict());
        verify(leaderboard).onFirstSolve(7L);
        verify(persistence, never()).recordCompileError(anyLong(), anyString());
    }

    @Test
    void wrongAnswerFlow_savesWaAndNoLeaderboardBump() throws Exception {
        jobWithOneCase("5");
        when(runner.run(any(), any(), any(), anyInt(), eq(true))).thenReturn(ok("", 0));
        when(runner.run(any(), any(), any(), anyInt(), eq(false))).thenReturn(ok("6\n", 0));
        when(persistence.saveResults(eq(1L), eq(SubmissionStatus.WRONG_ANSWER), eq(0), anyInt(), anyList()))
                .thenReturn(null);

        judge.judgeAsync(1L);

        verify(persistence).saveResults(eq(1L), eq(SubmissionStatus.WRONG_ANSWER), eq(0), anyInt(), anyList());
        verify(leaderboard, never()).onFirstSolve(anyLong());
    }

    @Test
    void timeoutFlow_savesTle() throws Exception {
        jobWithOneCase("5");
        when(runner.run(any(), any(), any(), anyInt(), eq(true))).thenReturn(ok("", 0));
        when(runner.run(any(), any(), any(), anyInt(), eq(false)))
                .thenReturn(new RunResult("", "timed out", -1, true, false, false, 4000));

        judge.judgeAsync(1L);

        verify(persistence).saveResults(eq(1L), eq(SubmissionStatus.TIME_LIMIT_EXCEEDED), eq(0), anyInt(), anyList());
    }

    @Test
    void compileErrorFlow_recordsCeAndSkipsRun() throws Exception {
        jobWithOneCase("5");
        when(runner.run(any(), any(), any(), anyInt(), eq(true)))
                .thenReturn(new RunResult("", "error: expected ';'", 1, false, false, false, 20));

        judge.judgeAsync(1L);

        verify(persistence).recordCompileError(eq(1L), contains("expected ';'"));
        verify(persistence, never()).saveResults(anyLong(), any(), anyInt(), anyInt(), anyList());
        verify(runner, never()).run(any(), any(), any(), anyInt(), eq(false)); // never ran test cases
    }

    @Test
    void beginJudgingFailure_marksInternalError() {
        when(persistence.beginJudging(1L)).thenThrow(new RuntimeException("db down"));

        judge.judgeAsync(1L);

        verify(persistence).markInternalError(eq(1L), contains("db down"));
    }
}
