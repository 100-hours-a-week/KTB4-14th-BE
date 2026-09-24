package com.audigo.domain.travel.entity;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "itinerary_days")
public class ItineraryDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_id", nullable = false)
    private TravelPlan travelPlan;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ItineraryDay() {
    }

    private ItineraryDay(TravelPlan travelPlan, int dayNumber, LocalDate travelDate) {
        this.travelPlan = Objects.requireNonNull(travelPlan, "여행 계획은 필수입니다.");
        if (dayNumber < 1) {
            throw new IllegalArgumentException("일차는 1 이상이어야 합니다.");
        }
        this.dayNumber = dayNumber;
        this.travelDate = Objects.requireNonNull(travelDate, "여행 날짜는 필수입니다.");
    }

    public static ItineraryDay create(TravelPlan travelPlan, int dayNumber, LocalDate travelDate) {
        return new ItineraryDay(travelPlan, dayNumber, travelDate);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public TravelPlan getTravelPlan() {
        return travelPlan;
    }

    public int getDayNumber() {
        return dayNumber;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
