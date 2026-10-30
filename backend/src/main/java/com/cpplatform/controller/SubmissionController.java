package com.cpplatform.controller;

import com.cpplatform.dto.SubmissionDtos.*;
import com.cpplatform.entity.User;
import com.cpplatform.service.SubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    @PostMapping
    public ResponseEntity<SubmissionView> submit(@Valid @RequestBody SubmitRequest req,
                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(submissionService.submit(req, user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubmissionView> get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(submissionService.getById(id, user));
    }

    @GetMapping("/me")
    public ResponseEntity<Page<SubmissionView>> mySubmissions(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(submissionService.listForUser(user.getId(), PageRequest.of(page, size)));
    }
}
