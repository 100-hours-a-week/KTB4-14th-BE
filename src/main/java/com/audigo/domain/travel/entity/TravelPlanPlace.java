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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;

@Entity
@Table(
        name = "travel_plan_places",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_travel_plan_places_plan_place",
                columnNames = {"travel_plan_id", "place_id"}
        )
)
public class TravelPlanPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_id", nullable = false)
    private TravelPlan travelPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 30)
    private PlaceType placeType;

    // 사용자가 직접 요청한 장소 / Ai가 추천한 장소 구분용
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private TravelPlaceSource source;

    @Column(name = "place_order", nullable = false)
    private int placeOrder;

    protected TravelPlanPlace() {
    }

    private TravelPlanPlace(
            TravelPlan travelPlan,
            Place place,
            PlaceType placeType,
            TravelPlaceSource source,
            int placeOrder
    ) {
        this.travelPlan = Objects.requireNonNull(travelPlan, "여행 계획은 필수입니다.");
        this.place = Objects.requireNonNull(place, "장소는 필수입니다.");
        this.placeType = Objects.requireNonNull(placeType, "장소 유형은 필수입니다.");
        this.source = Objects.requireNonNull(source, "장소 출처는 필수입니다.");
        if (placeOrder < 1) {
            throw new IllegalArgumentException("장소 순서는 1 이상이어야 합니다.");
        }
        this.placeOrder = placeOrder;
    }

    public static TravelPlanPlace create(
            TravelPlan travelPlan,
            Place place,
            PlaceType placeType,
            TravelPlaceSource source,
            int placeOrder
    ) {
        return new TravelPlanPlace(travelPlan, place, placeType, source, placeOrder);
    }

    public Long getId() {
        return id;
    }

    public TravelPlan getTravelPlan() {
        return travelPlan;
    }

    public Place getPlace() {
        return place;
    }

    public PlaceType getPlaceType() {
        return placeType;
    }

    public TravelPlaceSource getSource() {
        return source;
    }

    public int getPlaceOrder() {
        return placeOrder;
    }
}
