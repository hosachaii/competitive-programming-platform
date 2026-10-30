package com.cpplatform.repository;

import com.cpplatform.entity.SubmissionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubmissionResultRepository extends JpaRepository<SubmissionResult, Long> {
    List<SubmissionResult> findBySubmissionId(Long submissionId);
}
