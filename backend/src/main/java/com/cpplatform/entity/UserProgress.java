package com.cpplatform.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_progress", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "problem_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(nullable = false)
    @Builder.Default
    private boolean solved = false;

    @Column(name = "best_submission_id")
    private Long bestSubmissionId;

    @Builder.Default
    private Integer attempts = 0;
}
