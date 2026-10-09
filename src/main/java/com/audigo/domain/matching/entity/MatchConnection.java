package com.audigo.domain.matching.entity;

import com.audigo.domain.user.entity.User;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "match_connections")
public class MatchConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_request_id", nullable = false)
    private MatchingRequest matchingRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User targetUser;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchConnectionStatus status;

    protected MatchConnection() {
    }

    private MatchConnection(MatchingRequest matchingRequest, User targetUser) {
        this.matchingRequest = Objects.requireNonNull(matchingRequest, "매칭 요청은 필수입니다.");
        this.targetUser = Objects.requireNonNull(targetUser, "매칭 상대 사용자는 필수입니다.");
        this.status = MatchConnectionStatus.ACTIVE;
    }

    public static MatchConnection create(MatchingRequest matchingRequest, User targetUser) {
        return new MatchConnection(matchingRequest, targetUser);
    }

    @PrePersist
    void prePersist() {
        if (selectedAt == null) {
            selectedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public MatchingRequest getMatchingRequest() {
        return matchingRequest;
    }

    public User getTargetUser() {
        return targetUser;
    }

    public LocalDateTime getSelectedAt() {
        return selectedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public MatchConnectionStatus getStatus() {
        return status;
    }
}
