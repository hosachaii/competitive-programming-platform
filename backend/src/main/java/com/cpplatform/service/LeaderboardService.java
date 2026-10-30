package com.cpplatform.service;

import com.cpplatform.dto.LeaderboardDtos.LeaderboardEntry;
import com.cpplatform.entity.User;
import com.cpplatform.repository.UserProgressRepository;
import com.cpplatform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Leaderboard ranked by number of distinct problems solved.
 *
 * Redis holds a sorted set ("leaderboard:solved") whose members are user ids and
 * whose scores are solved-counts. Postgres remains the source of truth: the set is
 * rebuilt from it on startup and incremented on each user's FIRST accept of a
 * problem, so Redis is a fast cache. Reads use ZREVRANGE WITHSCORES. If Redis is
 * unavailable we log a warning and fall back to computing the ranking from Postgres.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeaderboardService {

    static final String KEY = "leaderboard:solved";

    private final UserRepository userRepository;
    private final UserProgressRepository userProgressRepository;
    private final StringRedisTemplate redis;

    /** Rebuild the sorted set from Postgres once the app (and the data seeder) are ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildFromPostgres() {
        try {
            ZSetOperations<String, String> z = redis.opsForZSet();
            redis.delete(KEY);
            List<Object[]> rows = userProgressRepository.countSolvedGroupedByUser();
            for (Object[] row : rows) {
                Long userId = (Long) row[0];
                long count = ((Number) row[1]).longValue();
                z.add(KEY, String.valueOf(userId), count);
            }
            log.info("Rebuilt Redis leaderboard from Postgres: {} ranked users", rows.size());
        } catch (Exception e) {
            log.warn("Could not rebuild Redis leaderboard (Redis unavailable?); " +
                    "reads will fall back to Postgres. Cause: {}", e.toString());
        }
    }

    /** Called when a user solves a problem for the FIRST time: bump their score by one. */
    public void onFirstSolve(Long userId) {
        try {
            redis.opsForZSet().incrementScore(KEY, String.valueOf(userId), 1);
        } catch (Exception e) {
            log.warn("Redis ZINCRBY failed for user {} (leaderboard will self-heal on next " +
                    "rebuild/read fallback). Cause: {}", userId, e.toString());
        }
    }

    public List<LeaderboardEntry> top(int limit) {
        try {
            return topFromRedis(limit);
        } catch (Exception e) {
            log.warn("Redis leaderboard read failed; falling back to Postgres. Cause: {}", e.toString());
            return topFromPostgres(limit);
        }
    }

    private List<LeaderboardEntry> topFromRedis(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(KEY, 0, limit - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        // Resolve usernames in a single query rather than one lookup per row.
        List<Long> userIds = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();
        Map<Long, String> usernames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));

        List<LeaderboardEntry> entries = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            Long userId = Long.valueOf(t.getValue());
            String username = usernames.get(userId);
            if (username == null) continue; // user deleted since the set was built
            long solved = t.getScore() == null ? 0 : Math.round(t.getScore());
            entries.add(new LeaderboardEntry(userId, username, solved));
        }
        return entries;
    }

    /** Original Postgres ranking, kept as the fallback when Redis is down. */
    private List<LeaderboardEntry> topFromPostgres(int limit) {
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
