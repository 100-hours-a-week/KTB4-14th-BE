package com.audigo.domain.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "matching_attempt_candidates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_matching_attempt_candidate",
                columnNames = {"matching_attempt_id", "candidate_user_id"}
        )
)
public class MatchingAttemptCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_attempt_id", nullable = false)
    private MatchingAttempt matchingAttempt;

    @Column(name = "candidate_user_id", nullable = false)
    private Long candidateUserId;

    @Column(name = "match_rate")
    private Integer matchRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MatchingAttemptCandidate() {
    }

    private MatchingAttemptCandidate(MatchingAttempt matchingAttempt, Long candidateUserId, Integer matchRate) {
        this.matchingAttempt = Objects.requireNonNull(matchingAttempt, "매칭 시도는 필수입니다.");
        this.candidateUserId = Objects.requireNonNull(candidateUserId, "후보 사용자 ID는 필수입니다.");
        this.matchRate = matchRate;
    }

    public static MatchingAttemptCandidate create(
            MatchingAttempt matchingAttempt,
            Long candidateUserId,
            Integer matchRate
    ) {
        return new MatchingAttemptCandidate(matchingAttempt, candidateUserId, matchRate);
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getCandidateUserId() {
        return candidateUserId;
    }

    public Integer getMatchRate() {
        return matchRate;
    }
}
