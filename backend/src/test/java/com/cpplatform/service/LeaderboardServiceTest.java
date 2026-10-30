package com.cpplatform.service;

import com.cpplatform.dto.LeaderboardDtos.LeaderboardEntry;
import com.cpplatform.entity.Role;
import com.cpplatform.entity.User;
import com.cpplatform.repository.UserProgressRepository;
import com.cpplatform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeaderboardServiceTest {

    private UserRepository users;
    private UserProgressRepository progress;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        progress = mock(UserProgressRepository.class);
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        service = new LeaderboardService(users, progress, redis);
    }

    private User user(long id, String name) {
        return User.builder().id(id).email(id + "@x.com").username(name).passwordHash("h").role(Role.USER).build();
    }

    @Test
    void onFirstSolveIncrementsSortedSetByOne() {
        service.onFirstSolve(7L);
        verify(zset).incrementScore(LeaderboardService.KEY, "7", 1);
    }

    @Test
    void topReadsSortedSetAndResolvesUsernamesInOrder() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("5", 3.0)); // highest first
        tuples.add(new DefaultTypedTuple<>("7", 1.0));
        when(zset.reverseRangeWithScores(LeaderboardService.KEY, 0, 4)).thenReturn(tuples);
        when(users.findAllById(List.of(5L, 7L))).thenReturn(List.of(user(5, "alice"), user(7, "bob")));

        List<LeaderboardEntry> top = service.top(5);

        assertEquals(2, top.size());
        assertEquals("alice", top.get(0).username());
        assertEquals(3, top.get(0).solvedCount());
        assertEquals("bob", top.get(1).username());
        assertEquals(1, top.get(1).solvedCount());
        verify(users, never()).findAll(); // used the Redis path, not the Postgres fallback
    }

    @Test
    void fallsBackToPostgresWhenRedisFails() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        when(users.findAll()).thenReturn(List.of(user(5, "alice"), user(7, "bob")));
        when(progress.countByUserIdAndSolvedTrue(5L)).thenReturn(3L);
        when(progress.countByUserIdAndSolvedTrue(7L)).thenReturn(1L);

        List<LeaderboardEntry> top = service.top(5);

        assertEquals(2, top.size());
        assertEquals("alice", top.get(0).username()); // sorted by solved count desc
        assertEquals(3, top.get(0).solvedCount());
    }
}
