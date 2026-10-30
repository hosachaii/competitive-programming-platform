package com.cpplatform.service;

import com.cpplatform.dto.SubmissionDtos.*;
import com.cpplatform.entity.*;
import com.cpplatform.exception.ApiException;
import com.cpplatform.repository.SubmissionRepository;
import com.cpplatform.repository.SubmissionResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionResultRepository submissionResultRepository;
    private final ProblemService problemService;
    private final JudgeService judgeService;

    @Value("${submission.rate-limit-seconds}")
    private long rateLimitSeconds;

    // Per-user timestamp of the last accepted submission. In-memory, so it is
    // per-instance; a multi-instance deployment would move this to Redis (which is
    // already provisioned) so the window is shared across judge nodes.
    private final ConcurrentHashMap<Long, Long> lastSubmissionAt = new ConcurrentHashMap<>();

    public SubmissionView submit(SubmitRequest req, User user) {
        enforceRateLimit(user.getId());
        Problem problem = problemService.getEntityById(req.problemId());

        Submission submission = Submission.builder()
                .user(user)
                .problem(problem)
                .language(req.language())
                .sourceCode(req.sourceCode())
                .status(SubmissionStatus.PENDING)
                .build();
        submission = submissionRepository.save(submission);
        lastSubmissionAt.put(user.getId(), System.currentTimeMillis());

        judgeService.judgeAsync(submission.getId()); // fires async, returns immediately

        return toView(submission, List.of());
    }

    private void enforceRateLimit(Long userId) {
        long windowMs = rateLimitSeconds * 1000L;
        Long last = lastSubmissionAt.get(userId);
        if (last != null) {
            long elapsed = System.currentTimeMillis() - last;
            if (elapsed < windowMs) {
                long waitSec = (windowMs - elapsed + 999) / 1000; // round up
                throw new ApiException(
                        "You're submitting too fast. Please wait " + waitSec +
                                " second" + (waitSec == 1 ? "" : "s") + " before submitting again.",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        }
    }

     public SubmissionView getById(Long id, User requester) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new ApiException("Submission not found", HttpStatus.NOT_FOUND));

        if (!submission.getUser().getId().equals(requester.getId()) && requester.getRole() != Role.ADMIN) {
            throw new ApiException("Not authorized to view this submission", HttpStatus.FORBIDDEN);
        }

        var results = submissionResultRepository.findBySubmissionId(id);
        return toView(submission, results);
    }

    public Page<SubmissionView> listForUser(Long userId, Pageable pageable) {
        return submissionRepository.findByUserIdOrderBySubmittedAtDesc(userId, pageable)
                .map(s -> toView(s, List.of()));
    }

    private SubmissionView toView(Submission s, List<SubmissionResult> results) {
        var resultViews = results.stream()
                .map(r -> new TestCaseResultView(
                        r.getTestCase().getId(),
                        r.getTestCase().isPublic(),
                        r.getVerdict(),
                        r.getRuntimeMs(),
                        r.getMemoryKb()))
                .toList();

        return new SubmissionView(
                s.getId(), s.getStatus(), s.getScore(), s.getRuntimeMs(), s.getMemoryKb(),
                s.getErrorMessage(), s.getSubmittedAt(), resultViews
        );
    }
}
