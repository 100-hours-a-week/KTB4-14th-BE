package com.audigo.domain.travel.entity;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "itinerary_items")
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itinerary_day_id", nullable = false)
    private ItineraryDay itineraryDay;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_place_id", nullable = false)
    private TravelPlanPlace travelPlanPlace;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30)
    private ItineraryItemType itemType;

    // 현재 ERD에는 진행 상태가 없어 일정 완료 API를 위해 추가한 진행 필드
    @Column(name = "is_completed", nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ItineraryItem() {
    }

    private ItineraryItem(
            ItineraryDay itineraryDay,
            TravelPlanPlace travelPlanPlace,
            int sequence,
            LocalTime startTime,
            LocalTime endTime,
            ItineraryItemType itemType
    ) {
        this.itineraryDay = Objects.requireNonNull(itineraryDay, "일정 날짜는 필수입니다.");
        this.travelPlanPlace = Objects.requireNonNull(travelPlanPlace, "일정 장소는 필수입니다.");
        if (sequence < 1) {
            throw new IllegalArgumentException("일정 순서는 1 이상이어야 합니다.");
        }
        this.sequence = sequence;
        this.startTime = Objects.requireNonNull(startTime, "시작 시간은 필수입니다.");
        this.endTime = Objects.requireNonNull(endTime, "종료 시간은 필수입니다.");
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("종료 시간은 시작 시간보다 늦어야 합니다.");
        }
        this.itemType = Objects.requireNonNull(itemType, "일정 항목 유형은 필수입니다.");
    }

    public static ItineraryItem create(
            ItineraryDay itineraryDay,
            TravelPlanPlace travelPlanPlace,
            int sequence,
            LocalTime startTime,
            LocalTime endTime,
            ItineraryItemType itemType
    ) {
        return new ItineraryItem(itineraryDay, travelPlanPlace, sequence, startTime, endTime, itemType);
    }

    public void updateCompletion(boolean completed, LocalDateTime completedAt) {
        this.completed = completed;
        this.completedAt = completed ? completedAt : null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public ItineraryDay getItineraryDay() {
        return itineraryDay;
    }

    public TravelPlanPlace getTravelPlanPlace() {
        return travelPlanPlace;
    }

    public int getSequence() {
        return sequence;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public ItineraryItemType getItemType() {
        return itemType;
    }

    public boolean isCompleted() {
        return completed;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}
