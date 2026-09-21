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
import java.util.Objects;

@Entity
@Table(name = "route_segments")
public class RouteSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_id", nullable = false)
    private TravelPlan travelPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_itinerary_item_id", nullable = false)
    private ItineraryItem fromItineraryItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_itinerary_item_id", nullable = false)
    private ItineraryItem toItineraryItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", nullable = false, length = 20)
    private TravelTransportType transportType;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "distance_meter")
    private Integer distanceMeter;

    @Column(name = "cost")
    private Integer cost;

    @Column(name = "`order`", nullable = false)
    private int order;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected RouteSegment() {
    }

    private RouteSegment(
            TravelPlan travelPlan,
            ItineraryItem fromItineraryItem,
            ItineraryItem toItineraryItem,
            TravelTransportType transportType,
            Integer durationMinutes,
            Integer distanceMeter,
            Integer cost,
            int order
    ) {
        this.travelPlan = Objects.requireNonNull(travelPlan, "여행 계획은 필수입니다.");
        this.fromItineraryItem = Objects.requireNonNull(fromItineraryItem, "출발 일정은 필수입니다.");
        this.toItineraryItem = Objects.requireNonNull(toItineraryItem, "도착 일정은 필수입니다.");
        this.transportType = Objects.requireNonNull(transportType, "이동 수단은 필수입니다.");
        if (fromItineraryItem == toItineraryItem) {
            throw new IllegalArgumentException("출발 일정과 도착 일정은 달라야 합니다.");
        }
        if (durationMinutes != null && durationMinutes < 0) {
            throw new IllegalArgumentException("이동 시간은 음수일 수 없습니다.");
        }
        if (distanceMeter != null && distanceMeter < 0) {
            throw new IllegalArgumentException("이동 거리는 음수일 수 없습니다.");
        }
        if (cost != null && cost < 0) {
            throw new IllegalArgumentException("이동 비용은 음수일 수 없습니다.");
        }
        if (order < 1) {
            throw new IllegalArgumentException("이동 순서는 1 이상이어야 합니다.");
        }
        this.durationMinutes = durationMinutes;
        this.distanceMeter = distanceMeter;
        this.cost = cost;
        this.order = order;
    }

    public static RouteSegment create(
            TravelPlan travelPlan,
            ItineraryItem fromItineraryItem,
            ItineraryItem toItineraryItem,
            TravelTransportType transportType,
            Integer durationMinutes,
            Integer distanceMeter,
            Integer cost,
            int order
    ) {
        return new RouteSegment(
                travelPlan,
                fromItineraryItem,
                toItineraryItem,
                transportType,
                durationMinutes,
                distanceMeter,
                cost,
                order
        );
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

    public TravelPlan getTravelPlan() {
        return travelPlan;
    }

    public ItineraryItem getFromItineraryItem() {
        return fromItineraryItem;
    }

    public ItineraryItem getToItineraryItem() {
        return toItineraryItem;
    }

    public TravelTransportType getTransportType() {
        return transportType;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public Integer getDistanceMeter() {
        return distanceMeter;
    }

    public Integer getCost() {
        return cost;
    }

    public int getOrder() {
        return order;
    }
}
