package com.cpplatform.controller;

import com.cpplatform.dto.LeaderboardDtos.LeaderboardEntry;
import com.cpplatform.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping
    public ResponseEntity<List<LeaderboardEntry>> top(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(leaderboardService.top(limit));
    }
}
