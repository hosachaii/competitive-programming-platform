package com.cpplatform.repository;

import com.cpplatform.entity.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    Page<Submission> findByUserIdOrderBySubmittedAtDesc(Long userId, Pageable pageable);
    List<Submission> findByUserIdAndProblemIdOrderBySubmittedAtDesc(Long userId, Long problemId);
    Page<Submission> findByProblemIdOrderBySubmittedAtDesc(Long problemId, Pageable pageable);
    long countByProblemId(Long problemId);
}
