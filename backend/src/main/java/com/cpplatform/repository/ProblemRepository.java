package com.cpplatform.repository;

import com.cpplatform.entity.Problem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProblemRepository extends JpaRepository<Problem, Long> {
    Optional<Problem> findBySlug(String slug);
    Page<Problem> findByDifficulty(Problem.Difficulty difficulty, Pageable pageable);
}
