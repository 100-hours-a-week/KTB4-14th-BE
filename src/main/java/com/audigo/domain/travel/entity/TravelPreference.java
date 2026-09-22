package com.audigo.domain.travel.entity;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
@Table(name = "travel_preferences")
public class TravelPreference {

    private static final int MAX_BUDGET = 3_000_000;
    private static final int MAX_EXTRA_REQUEST_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_id", nullable = false, unique = true)
    private TravelPlan travelPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "pace_type", nullable = false, length = 20)
    private TravelPaceType paceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", nullable = false, length = 30)
    private TravelTransportType transportType;

    @Column(name = "budget_min", nullable = false)
    private int budgetMin;

    @Column(name = "budget_max", nullable = false)
    private int budgetMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_type", nullable = false, length = 10)
    private BudgetType budgetType;

    @Column(name = "distance_preference", nullable = false)
    private int distancePreference;

    @Column(name = "extra_request", length = MAX_EXTRA_REQUEST_LENGTH)
    private String extraRequest;

    @OneToMany(mappedBy = "travelPreference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TravelPreferenceTheme> themes = new ArrayList<>();

    @OneToMany(mappedBy = "travelPreference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TravelPreferenceFood> foods = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TravelPreference() {
    }

    private TravelPreference(
            TravelPlan travelPlan,
            TravelPaceType paceType,
            TravelTransportType transportType,
            int budgetMin,
            int budgetMax,
            BudgetType budgetType,
            int distancePreference,
            List<TravelThemeType> themes,
            List<FoodType> foods,
            String extraRequest
    ) {
        this.travelPlan = Objects.requireNonNull(travelPlan, "여행 계획은 필수입니다.");
        this.paceType = Objects.requireNonNull(paceType, "여행 속도는 필수입니다.");
        this.transportType = Objects.requireNonNull(transportType, "이동 수단은 필수입니다.");
        validateBudget(budgetMin, budgetMax);
        this.budgetMin = budgetMin;
        this.budgetMax = budgetMax;
        this.budgetType = Objects.requireNonNull(budgetType, "예산 통화는 필수입니다.");
        this.distancePreference = validateDistancePreference(distancePreference);
        this.extraRequest = normalizeExtraRequest(extraRequest);
        addThemes(themes);
        addFoods(foods);
    }

    public static TravelPreference create(
            TravelPlan travelPlan,
            TravelPaceType paceType,
            TravelTransportType transportType,
            int budgetMin,
            int budgetMax,
            BudgetType budgetType,
            int distancePreference,
            List<TravelThemeType> themes,
            List<FoodType> foods,
            String extraRequest
    ) {
        return new TravelPreference(
                travelPlan,
                paceType,
                transportType,
                budgetMin,
                budgetMax,
                budgetType,
                distancePreference,
                themes,
                foods,
                extraRequest
        );
    }

    private void addThemes(List<TravelThemeType> values) {
        List<TravelThemeType> required = List.copyOf(Objects.requireNonNull(values, "테마 목록은 필수입니다."));
        if (required.size() > 3 || new HashSet<>(required).size() != required.size()) {
            throw new IllegalArgumentException("테마는 3개 이하의 중복 없는 값이어야 합니다.");
        }
        required.forEach(theme -> themes.add(TravelPreferenceTheme.create(this, theme)));
    }

    private void addFoods(List<FoodType> values) {
        List<FoodType> required = List.copyOf(Objects.requireNonNull(values, "음식 목록은 필수입니다."));
        if (new HashSet<>(required).size() != required.size()) {
            throw new IllegalArgumentException("음식 선호는 중복될 수 없습니다.");
        }
        required.forEach(food -> foods.add(TravelPreferenceFood.create(this, food)));
    }

    private static void validateBudget(int min, int max) {
        if (min < 0 || max > MAX_BUDGET || min > max) {
            throw new IllegalArgumentException("최소 예산은 최대 예산보다 작거나 같아야 하며, 0원 이상 3,000,000원 이하여야 합니다.");
        }
    }

    private static int validateDistancePreference(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("거리 선호도는 0 이상 100 이하여야 합니다.");
        }
        return value;
    }

    private static String normalizeExtraRequest(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_EXTRA_REQUEST_LENGTH) {
            throw new IllegalArgumentException("추가 요청은 300자 이하여야 합니다.");
        }
        return normalized;
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

    public TravelPaceType getPaceType() {
        return paceType;
    }

    public TravelTransportType getTransportType() {
        return transportType;
    }

    public int getBudgetMin() {
        return budgetMin;
    }

    public int getBudgetMax() {
        return budgetMax;
    }

    public BudgetType getBudgetType() {
        return budgetType;
    }

    public int getDistancePreference() {
        return distancePreference;
    }

    public String getExtraRequest() {
        return extraRequest;
    }

    public List<TravelPreferenceTheme> getThemes() {
        return Collections.unmodifiableList(themes);
    }

    public List<TravelPreferenceFood> getFoods() {
        return Collections.unmodifiableList(foods);
    }
}
