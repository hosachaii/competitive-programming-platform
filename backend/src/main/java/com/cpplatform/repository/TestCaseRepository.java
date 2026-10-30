package com.cpplatform.repository;

import com.cpplatform.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    List<TestCase> findByProblemId(Long problemId);
    List<TestCase> findByProblemIdAndIsPublicTrue(Long problemId);

    @Modifying
    @Query("delete from TestCase t where t.problem.id = :problemId")
    void deleteByProblemId(Long problemId);
}
