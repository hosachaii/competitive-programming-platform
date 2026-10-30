package com.cpplatform.service;

import com.cpplatform.dto.LeaderboardDtos.LeaderboardEntry;
import com.cpplatform.repository.UserProgressRepository;
import com.cpplatform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * MVP leaderboard computed directly from Postgres. For real scale, swap this for
 * a Redis sorted set (ZINCRBY on each accepted verdict, ZREVRANGE to read) so ranking
 * is O(log n) instead of a full table scan — see architecture.md §3.
 */
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserRepository userRepository;
    private final UserProgressRepository userProgressRepository;

    public List<LeaderboardEntry> top(int limit) {
        return userRepository.findAll().stream()
                .map(u -> new LeaderboardEntry(
                        u.getId(), u.getUsername(),
                        userProgressRepository.countByUserIdAndSolvedTrue(u.getId())))
                .filter(e -> e.solvedCount() > 0)
                .sorted(Comparator.comparingLong(LeaderboardEntry::solvedCount).reversed())
                .limit(limit)
                .toList();
    }
}
