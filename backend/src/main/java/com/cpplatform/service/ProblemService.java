package com.cpplatform.service;

import com.cpplatform.dto.ProblemDtos.*;
import com.cpplatform.entity.Problem;
import com.cpplatform.entity.Tag;
import com.cpplatform.entity.TestCase;
import com.cpplatform.exception.ApiException;
import com.cpplatform.repository.ProblemRepository;
import com.cpplatform.repository.TagRepository;
import com.cpplatform.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final TagRepository tagRepository;
    private final TestCaseRepository testCaseRepository;

    @Transactional
    public ProblemSummary create(CreateProblemRequest req, Long adminUserId) {
        String slug = slugify(req.title());
        if (problemRepository.findBySlug(slug).isPresent()) {
            slug = slug + "-" + System.currentTimeMillis() % 10000;
        }

        Problem problem = Problem.builder()
                .title(req.title())
                .slug(slug)
                .difficulty(req.difficulty())
                .statement(req.statement())
                .constraints(req.constraints())
                .timeLimitMs(req.timeLimitMs() != null ? req.timeLimitMs() : 2000)
                .memoryLimitMb(req.memoryLimitMb() != null ? req.memoryLimitMb() : 256)
                .createdBy(adminUserId)
                .build();

        if (req.tags() != null) {
            Set<Tag> tags = req.tags().stream()
                    .map(name -> tagRepository.findByName(name)
                            .orElseGet(() -> tagRepository.save(Tag.builder().name(name).build())))
                    .collect(Collectors.toSet());
            problem.setTags(tags);
        }

        problem = problemRepository.save(problem);
        saveTestCases(problem, req.testCases());

        return toSummary(problem);
    }

    public Page<ProblemSummary> list(Problem.Difficulty difficulty, Pageable pageable) {
        Page<Problem> page = difficulty != null
                ? problemRepository.findByDifficulty(difficulty, pageable)
                : problemRepository.findAll(pageable);
        return page.map(this::toSummary);
    }

    @Transactional
    public ProblemDetail getBySlug(String slug) {
        Problem problem = problemRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException("Problem not found", HttpStatus.NOT_FOUND));

        List<PublicTestCase> publicCases = testCaseRepository.findByProblemIdAndIsPublicTrue(problem.getId())
                .stream().map(tc -> new PublicTestCase(tc.getInput(), tc.getExpectedOutput()))
                .toList();

        return new ProblemDetail(
                problem.getId(), problem.getTitle(), problem.getSlug(), problem.getDifficulty(),
                problem.getStatement(), problem.getConstraints(),
                problem.getTimeLimitMs(), problem.getMemoryLimitMb(),
                problem.getTags().stream().map(Tag::getName).collect(Collectors.toSet()),
                publicCases
        );
    }

    // Package-private: used by JudgeService to get ALL test cases (including hidden)
    public Problem getEntityById(Long id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new ApiException("Problem not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public AdminProblemDetail getForAdmin(Long id) {
        Problem problem = getEntityById(id);
        List<AdminTestCase> testCases = testCaseRepository.findByProblemId(id).stream()
                .map(tc -> new AdminTestCase(tc.getId(), tc.getInput(), tc.getExpectedOutput(), tc.isPublic(), tc.getWeight()))
                .toList();

        return new AdminProblemDetail(
                problem.getId(), problem.getTitle(), problem.getSlug(), problem.getDifficulty(),
                problem.getStatement(), problem.getConstraints(),
                problem.getTimeLimitMs(), problem.getMemoryLimitMb(),
                problem.getTags().stream().map(Tag::getName).collect(Collectors.toSet()),
                testCases
        );
    }

    @Transactional
    public ProblemSummary update(Long id, CreateProblemRequest req) {
        Problem problem = getEntityById(id);

        problem.setTitle(req.title());
        problem.setDifficulty(req.difficulty());
        problem.setStatement(req.statement());
        problem.setConstraints(req.constraints());
        if (req.timeLimitMs() != null) problem.setTimeLimitMs(req.timeLimitMs());
        if (req.memoryLimitMb() != null) problem.setMemoryLimitMb(req.memoryLimitMb());
        // slug is intentionally left unchanged on edit — links to this problem stay valid

        Set<Tag> tags = req.tags() == null ? Set.of() : req.tags().stream()
                .map(name -> tagRepository.findByName(name)
                        .orElseGet(() -> tagRepository.save(Tag.builder().name(name).build())))
                .collect(Collectors.toSet());
        problem.setTags(tags);
        problem = problemRepository.save(problem);

        // full replace of test cases — simplest correct semantics for an edit form
        testCaseRepository.deleteByProblemId(id);
        saveTestCases(problem, req.testCases());

        return toSummary(problem);
    }

    @Transactional
    public void delete(Long id, long existingSubmissionCount) {
        Problem problem = getEntityById(id);
        if (existingSubmissionCount > 0) {
            throw new ApiException(
                    "Cannot delete a problem that already has submissions (" + existingSubmissionCount +
                            "). Consider leaving it in place, or removing submissions first if this is test data.",
                    HttpStatus.CONFLICT);
        }
        testCaseRepository.deleteByProblemId(id);
        problemRepository.delete(problem);
    }

    private void saveTestCases(Problem problem, List<TestCaseRequest> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            throw new ApiException("At least one test case is required", HttpStatus.BAD_REQUEST);
        }
        for (var tc : testCases) {
            testCaseRepository.save(TestCase.builder()
                    .problem(problem)
                    .input(tc.input())
                    .expectedOutput(tc.expectedOutput())
                    .isPublic(tc.isPublic())
                    .weight(tc.weight() != null ? tc.weight() : 1)
                    .build());
        }
    }

    private ProblemSummary toSummary(Problem p) {
        return new ProblemSummary(p.getId(), p.getTitle(), p.getSlug(), p.getDifficulty(),
                p.getTags().stream().map(Tag::getName).collect(Collectors.toSet()));
    }

    private String slugify(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD);
        String slug = Pattern.compile("[^\\p{ASCII}]").matcher(normalized).replaceAll("");
        slug = slug.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-");
        return slug;
    }
}
