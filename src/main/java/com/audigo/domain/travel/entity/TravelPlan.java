package com.audigo.domain.travel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "travel_plans")

public class TravelPlan {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(name = "arrival_datetime", nullable = false)
    private LocalDateTime arrivalDatetime;

    @Column(name = "departure_datetime", nullable = false)
    private LocalDateTime departureDatetime;

    @Column(name = "headcount", nullable = false)
    private int headCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "companion_type", nullable = false, length = 20)
    private CompanionType companionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TravelPlanStatus status;

    @OneToOne(mappedBy = "travelPlan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private TravelPreference preference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate(){
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;
    }
    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = LocalDateTime.now();
    }

    protected TravelPlan(){}

    private TravelPlan(Long userId, Region region, LocalDateTime arrivalDatetime,
                       LocalDateTime departureDatetime, int headCount, CompanionType companionType){
        if (userId == null){
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
        if (region == null){
            throw new IllegalArgumentException("지역 ID는 필수입니다.");
        }
        if (arrivalDatetime == null){
            throw new IllegalArgumentException("도착 시간은 필수입니다.");
        }
        if (departureDatetime == null){
            throw new IllegalArgumentException("출발 시간은 필수입니다.");
        }
        if (companionType == null) {
            throw new IllegalArgumentException("동행자 유형은 필수입니다.");
        }
        if (headCount < 1 || headCount > 30){
            throw new IllegalArgumentException("인원은 1명 이상 30명 이하여야 합니다.");
        }
        if (!arrivalDatetime.isBefore(departureDatetime)){
            throw new IllegalArgumentException("도착 시간은 출발 시간보다 빨라야 합니다");
        }
        if (companionType == CompanionType.SOLO && headCount != 1) {
            throw new IllegalArgumentException("혼자 여행의 인원은 1명이어야 합니다.");
        }
        if (companionType != CompanionType.SOLO && headCount < 2) {
            throw new IllegalArgumentException("동행 여행의 인원은 2명 이상이어야 합니다.");
        }

        this.userId = userId;
        this.region = region;
        this.arrivalDatetime = arrivalDatetime;
        this.departureDatetime = departureDatetime;
        this.headCount = headCount;
        this.companionType = companionType;
        this.status = TravelPlanStatus.GENERATING;
    }

    public static TravelPlan create(
            Long userId,
            Region region,
            LocalDateTime arrivalDatetime,
            LocalDateTime departureDatetime,
            int headCount,
            CompanionType companionType) {
        return new TravelPlan(userId,
                region,
                arrivalDatetime,
                departureDatetime,
                headCount,
                companionType);
    }

    public void attachPreference(TravelPreference preference) {
        if (this.preference != null) {
            throw new IllegalStateException("여행 취향은 한 번만 설정할 수 있습니다.");
        }
        this.preference = java.util.Objects.requireNonNull(preference, "여행 취향은 필수입니다.");
    }

    // getter
    public Long getId() {
        return id;
    }

    public Long getUserId(){
        return userId;
    }

    public Region getRegion(){
        return region;
    }

    public LocalDateTime getArrivalDatetime(){
        return arrivalDatetime;
    }

    public LocalDateTime getDepartureDatetime(){
        return departureDatetime;
    }

    public int getHeadCount(){
        return headCount;
    }

    public CompanionType getCompanionType(){
        return companionType;
    }

    public TravelPlanStatus getStatus(){
        return status;
    }

    public TravelPreference getPreference() {
        return preference;
    }

    public LocalDateTime getCreatedAt(){
        return createdAt;
    }

    public LocalDateTime getUpdatedAt(){
        return updatedAt;
    }
}
