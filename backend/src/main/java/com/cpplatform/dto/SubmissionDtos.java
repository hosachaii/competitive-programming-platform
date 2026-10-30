package com.cpplatform.dto;

import com.cpplatform.entity.Language;
import com.cpplatform.entity.SubmissionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class SubmissionDtos {

    // 64 KB of source is far more than any reasonable solution; rejecting larger
    // payloads up front keeps oversized bodies out of the DB and the judge.
    public static final int MAX_SOURCE_CODE_CHARS = 64 * 1024;

    public record SubmitRequest(
            @NotNull Long problemId,
            @NotNull Language language,
            @NotBlank
            @Size(max = MAX_SOURCE_CODE_CHARS, message = "Source code must be at most 64 KB")
            String sourceCode
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
