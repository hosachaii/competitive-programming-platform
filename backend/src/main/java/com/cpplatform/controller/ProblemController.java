package com.cpplatform.controller;

import com.cpplatform.dto.ProblemDtos.*;
import com.cpplatform.entity.Problem;
import com.cpplatform.entity.User;
import com.cpplatform.repository.SubmissionRepository;
import com.cpplatform.service.ProblemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;
    private final SubmissionRepository submissionRepository;

    @GetMapping
    public ResponseEntity<Page<ProblemSummary>> list(
            @RequestParam(required = false) Problem.Difficulty difficulty,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(problemService.list(difficulty, PageRequest.of(page, size)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ProblemDetail> get(@PathVariable String slug) {
        return ResponseEntity.ok(problemService.getBySlug(slug));
    }

    @GetMapping("/{id}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminProblemDetail> getForAdmin(@PathVariable Long id) {
        return ResponseEntity.ok(problemService.getForAdmin(id));
    }

    @PostMapping
    public ResponseEntity<ProblemSummary> create(@Valid @RequestBody CreateProblemRequest req,
                                                  @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(problemService.create(req, admin.getId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProblemSummary> update(@PathVariable Long id, @Valid @RequestBody CreateProblemRequest req) {
        return ResponseEntity.ok(problemService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        problemService.delete(id, submissionRepository.countByProblemId(id));
        return ResponseEntity.noContent().build();
    }
}
