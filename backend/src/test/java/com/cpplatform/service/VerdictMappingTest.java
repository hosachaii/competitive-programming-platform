package com.cpplatform.service;

import com.cpplatform.entity.SubmissionStatus;
import com.cpplatform.service.ContainerRunner.RunResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link JudgeService#mapVerdict} — the pure run-outcome -> verdict logic. */
class VerdictMappingTest {

    private static RunResult result(String stdout, int exit, boolean timedOut, boolean oom, boolean outExceeded) {
        return new RunResult(stdout, "", exit, timedOut, oom, outExceeded, 10);
    }

    @Test
    void acceptedWhenExitZeroAndOutputMatches() {
        assertEquals(SubmissionStatus.ACCEPTED,
                JudgeService.mapVerdict(result("42\n", 0, false, false, false), "42"));
    }

    @Test
    void wrongAnswerWhenExitZeroButOutputDiffers() {
        assertEquals(SubmissionStatus.WRONG_ANSWER,
                JudgeService.mapVerdict(result("41\n", 0, false, false, false), "42"));
    }

    @Test
    void timeoutWins() {
        // even if exit code / output would say otherwise, a timeout is TLE
        assertEquals(SubmissionStatus.TIME_LIMIT_EXCEEDED,
                JudgeService.mapVerdict(result("", -1, true, false, false), "42"));
    }

    @Test
    void outputFloodBeatsOomAndExitCode() {
        // a flooded run is killed (exit 137) but should read as OLE, not MLE/RE
        assertEquals(SubmissionStatus.OUTPUT_LIMIT_EXCEEDED,
                JudgeService.mapVerdict(result("AAAA", 137, false, false, true), "42"));
    }

    @Test
    void oomMapsToMemoryLimit() {
        assertEquals(SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
                JudgeService.mapVerdict(result("", 137, false, true, false), "42"));
    }

    @Test
    void nonZeroExitIsRuntimeError() {
        assertEquals(SubmissionStatus.RUNTIME_ERROR,
                JudgeService.mapVerdict(result("", 1, false, false, false), "42"));
        assertEquals(SubmissionStatus.RUNTIME_ERROR,
                JudgeService.mapVerdict(result("", 134, false, false, false), "42")); // SIGABRT
    }
}
