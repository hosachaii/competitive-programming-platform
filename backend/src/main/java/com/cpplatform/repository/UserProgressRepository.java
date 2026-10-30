package com.cpplatform.repository;

import com.cpplatform.entity.UserProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.List;

public interface UserProgressRepository extends JpaRepository<UserProgress, Long> {
    Optional<UserProgress> findByUserIdAndProblemId(Long userId, Long problemId);
    List<UserProgress> findByUserId(Long userId);
    long countByUserIdAndSolvedTrue(Long userId);

    /** [userId, solvedCount] for every user with at least one solve; used to rebuild the Redis leaderboard. */
    @Query("select up.user.id, count(up) from UserProgress up where up.solved = true group by up.user.id")
    List<Object[]> countSolvedGroupedByUser();
}
