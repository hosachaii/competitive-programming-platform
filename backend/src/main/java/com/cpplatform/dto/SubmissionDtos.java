package com.cpplatform.dto;

import com.cpplatform.entity.Language;
import com.cpplatform.entity.SubmissionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public class SubmissionDtos {

    public record SubmitRequest(
            @NotNull Long problemId,
            @NotNull Language language,
            @NotBlank String sourceCode
    ) {}

    public record SubmissionView(
            Long id, SubmissionStatus status, Integer score,
            Integer runtimeMs, Integer memoryKb, String errorMessage,
            Instant submittedAt, List<TestCaseResultView> results
    ) {}

    public record TestCaseResultView(
            Long testCaseId, boolean isPublic, SubmissionStatus verdict,
            Integer runtimeMs, Integer memoryKb
    ) {}
}
