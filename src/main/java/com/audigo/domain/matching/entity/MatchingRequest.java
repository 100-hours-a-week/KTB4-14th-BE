package com.audigo.domain.matching.entity;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "matching_requests")
public class MatchingRequest {

    public static final int MAX_THEME_COUNT = 3;
    public static final int MAX_RETRY_COUNT = 3;
    private static final int MAX_BUDGET = 3_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_companion_gender", nullable = false, length = 20)
    private PreferredCompanionGender preferredCompanionGender;

    @Enumerated(EnumType.STRING)
    @Column(name = "pace", nullable = false, length = 20)
    private TravelPaceType pace;

    @Column(name = "budget_min")
    private Integer budgetMin;

    @Column(name = "budget_max")
    private Integer budgetMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MatchingRequestStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @OneToMany(mappedBy = "matchingRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MatchingRequestTheme> themes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MatchingRequest() {
    }

    private MatchingRequest(
            User requester,
            PreferredCompanionGender preferredCompanionGender,
            TravelPaceType pace,
            Integer budgetMin,
            Integer budgetMax,
            List<TravelThemeType> themes
    ) {
        this.requester = Objects.requireNonNull(requester, "요청 사용자는 필수입니다.");
        this.preferredCompanionGender = Objects.requireNonNull(preferredCompanionGender, "선호 동행자 성별은 필수입니다.");
        this.pace = Objects.requireNonNull(pace, "여행 속도는 필수입니다.");
        validateBudget(budgetMin, budgetMax);
        this.budgetMin = budgetMin;
        this.budgetMax = budgetMax;
        this.status = MatchingRequestStatus.ACTIVE;
        this.retryCount = 0;
        addThemes(themes);
    }

    public static MatchingRequest create(
            User requester,
            PreferredCompanionGender preferredCompanionGender,
            TravelPaceType pace,
            Integer budgetMin,
            Integer budgetMax,
            List<TravelThemeType> themes
    ) {
        return new MatchingRequest(requester, preferredCompanionGender, pace, budgetMin, budgetMax, themes);
    }

    private void addThemes(List<TravelThemeType> values) {
        List<TravelThemeType> normalized = List.copyOf(Objects.requireNonNull(values, "선호 테마 목록은 필수입니다."));
        if (normalized.isEmpty()
                || normalized.size() > MAX_THEME_COUNT
                || new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("선호 테마는 1개 이상 3개 이하의 중복 없는 값이어야 합니다.");
        }
        normalized.forEach(theme -> themes.add(MatchingRequestTheme.create(this, theme)));
    }

    private void validateBudget(Integer min, Integer max) {
        if (min == null && max == null) {
            return;
        }
        if (min == null || max == null || min < 0 || max > MAX_BUDGET || min > max) {
            throw new IllegalArgumentException("예산 범위는 0원 이상 3,000,000원 이하이며 최소 예산은 최대 예산보다 작거나 같아야 합니다.");
        }
    }

    public void markConnected() {
        if (status == MatchingRequestStatus.CONNECTED) {
            return;
        }
        if (status != MatchingRequestStatus.ACTIVE && status != MatchingRequestStatus.EXHAUSTED) {
            throw new IllegalStateException("연결 가능한 매칭 세션이 아닙니다.");
        }
        status = MatchingRequestStatus.CONNECTED;
        endedAt = LocalDateTime.now();
    }

    public void markExhausted() {
        finish(MatchingRequestStatus.EXHAUSTED);
    }

    public void cancel() {
        finish(MatchingRequestStatus.CANCELLED);
    }

    public void completeRematch() {
        if (status != MatchingRequestStatus.ACTIVE) {
            throw new IllegalStateException("활성 매칭 세션만 재매칭할 수 있습니다.");
        }
        if (retryCount >= MAX_RETRY_COUNT) {
            throw new IllegalStateException("재매칭 가능 횟수를 초과했습니다.");
        }
        retryCount++;
        if (retryCount == MAX_RETRY_COUNT) {
            markExhausted();
        }
    }

    public int getRemainingRetryCount() {
        return Math.max(0, MAX_RETRY_COUNT - retryCount);
    }

    public boolean canRematch() {
        return status == MatchingRequestStatus.ACTIVE && retryCount < MAX_RETRY_COUNT;
    }

    private void finish(MatchingRequestStatus nextStatus) {
        if (status != MatchingRequestStatus.ACTIVE) {
            throw new IllegalStateException("활성 매칭 세션만 종료할 수 있습니다.");
        }
        status = nextStatus;
        endedAt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getRequester() {
        return requester;
    }

    public PreferredCompanionGender getPreferredCompanionGender() {
        return preferredCompanionGender;
    }

    public TravelPaceType getPace() {
        return pace;
    }

    public Integer getBudgetMin() {
        return budgetMin;
    }

    public Integer getBudgetMax() {
        return budgetMax;
    }

    public List<MatchingRequestTheme> getThemes() {
        return Collections.unmodifiableList(themes);
    }

    public MatchingRequestStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }
}
