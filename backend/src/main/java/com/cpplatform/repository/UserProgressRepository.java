package com.cpplatform.repository;

import com.cpplatform.entity.UserProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface UserProgressRepository extends JpaRepository<UserProgress, Long> {
    Optional<UserProgress> findByUserIdAndProblemId(Long userId, Long problemId);
    List<UserProgress> findByUserId(Long userId);
    long countByUserIdAndSolvedTrue(Long userId);
}
