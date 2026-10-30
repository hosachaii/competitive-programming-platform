package com.cpplatform.dto;

public class LeaderboardDtos {
    public record LeaderboardEntry(Long userId, String username, long solvedCount) {}
}
