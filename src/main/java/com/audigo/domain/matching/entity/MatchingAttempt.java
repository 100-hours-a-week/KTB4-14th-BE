package com.audigo.domain.matching.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "matching_attempts")
public class MatchingAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_request_id", nullable = false)
    private MatchingRequest matchingRequest;

    @Column(name = "retry_sequence", nullable = false)
    private int retrySequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchingAttemptStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @OneToMany(mappedBy = "matchingAttempt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MatchingAttemptCandidate> candidates = new ArrayList<>();

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected MatchingAttempt() {
    }

    private MatchingAttempt(MatchingRequest matchingRequest, int retrySequence) {
        this.matchingRequest = Objects.requireNonNull(matchingRequest, "매칭 요청은 필수입니다.");
        this.retrySequence = retrySequence;
        this.status = MatchingAttemptStatus.PROCESSING;
    }

    public static MatchingAttempt start(MatchingRequest matchingRequest, int retrySequence) {
        return new MatchingAttempt(matchingRequest, retrySequence);
    }

    public void succeed(List<CandidateScore> scores) {
        requireProcessing();
        scores.forEach(score -> candidates.add(
                MatchingAttemptCandidate.create(this, score.userId(), score.matchRate())
        ));
        status = MatchingAttemptStatus.SUCCEEDED;
        completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        requireProcessing();
        status = MatchingAttemptStatus.FAILED;
        failureReason = abbreviate(reason);
        completedAt = LocalDateTime.now();
    }

    private void requireProcessing() {
        if (status != MatchingAttemptStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 매칭 시도만 완료할 수 있습니다.");
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public MatchingRequest getMatchingRequest() {
        return matchingRequest;
    }

    public int getRetrySequence() {
        return retrySequence;
    }

    public MatchingAttemptStatus getStatus() {
        return status;
    }

    public List<MatchingAttemptCandidate> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public record CandidateScore(Long userId, Integer matchRate) {
    }
}
