package com.cpplatform.dto;

import com.cpplatform.entity.Problem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;

public class ProblemDtos {

    public record TestCaseRequest(
            @NotBlank String input,
            @NotBlank String expectedOutput,
            boolean isPublic,
            Integer weight
    ) {}

    public record CreateProblemRequest(
            @NotBlank String title,
            @NotNull Problem.Difficulty difficulty,
            @NotBlank String statement,
            String constraints,
            Integer timeLimitMs,
            Integer memoryLimitMb,
            Set<String> tags,
            List<TestCaseRequest> testCases
    ) {}

    public record ProblemSummary(
            Long id, String title, String slug, Problem.Difficulty difficulty, Set<String> tags
    ) {}

    public record ProblemDetail(
            Long id, String title, String slug, Problem.Difficulty difficulty,
            String statement, String constraints, Integer timeLimitMs, Integer memoryLimitMb,
            Set<String> tags, List<PublicTestCase> publicTestCases
    ) {}

    public record PublicTestCase(String input, String expectedOutput) {}

    public record AdminTestCase(Long id, String input, String expectedOutput, boolean isPublic, Integer weight) {}

    public record AdminProblemDetail(
            Long id, String title, String slug, Problem.Difficulty difficulty,
            String statement, String constraints, Integer timeLimitMs, Integer memoryLimitMb,
            Set<String> tags, List<AdminTestCase> testCases
    ) {}
}
