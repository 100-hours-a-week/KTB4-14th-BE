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

// 사용자용 여행 상태와 별도로 큐 대기·실행 상태와 실제 처리 시간을 관리
@Entity
@Table(name = "ai_generation_jobs")
public class TravelGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_plan_id", nullable = false)
    private TravelPlan travelPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private TravelGenerationJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TravelPlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 30)
    private TravelGenerationExecutionStatus executionStatus;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TravelGenerationJob() {
    }

    private TravelGenerationJob(TravelPlan travelPlan, TravelGenerationJobType jobType) {
        this.travelPlan = Objects.requireNonNull(travelPlan, "여행 계획은 필수입니다.");
        this.jobType = Objects.requireNonNull(jobType, "생성 작업 유형은 필수입니다.");
        this.status = TravelPlanStatus.GENERATING;
        this.executionStatus = TravelGenerationExecutionStatus.QUEUED;
    }

    public static TravelGenerationJob create(TravelPlan travelPlan) {
        return new TravelGenerationJob(travelPlan, TravelGenerationJobType.TRAVEL_ITINERARY);
    }

    public void markStarted() {
        this.status = TravelPlanStatus.GENERATING;
        this.executionStatus = TravelGenerationExecutionStatus.PROCESSING;
        this.errorMessage = null;
        this.endedAt = null;
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }

    public void complete() {
        this.status = TravelPlanStatus.COMPLETED;
        this.executionStatus = TravelGenerationExecutionStatus.COMPLETED;
        this.errorMessage = null;
        this.endedAt = LocalDateTime.now();
    }

    public void fail(String message) {
        this.status = TravelPlanStatus.FAILED;
        this.executionStatus = TravelGenerationExecutionStatus.FAILED;
        this.errorMessage = normalizeErrorMessage(message);
        this.endedAt = LocalDateTime.now();
    }

    private String normalizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "여행 생성에 실패했습니다.";
        }
        String normalized = message.trim();
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        if (this.executionStatus == null) {
            this.executionStatus = TravelGenerationExecutionStatus.QUEUED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (this.executionStatus != null) {
            return;
        }
        if (this.status == TravelPlanStatus.COMPLETED) {
            this.executionStatus = TravelGenerationExecutionStatus.COMPLETED;
        } else if (this.status == TravelPlanStatus.FAILED) {
            this.executionStatus = TravelGenerationExecutionStatus.FAILED;
        } else if (this.startedAt == null) {
            this.executionStatus = TravelGenerationExecutionStatus.QUEUED;
        } else {
            this.executionStatus = TravelGenerationExecutionStatus.PROCESSING;
        }
    }

    public Long getId() {
        return id;
    }

    public TravelPlan getTravelPlan() {
        return travelPlan;
    }

    public TravelGenerationJobType getJobType() {
        return jobType;
    }

    public TravelPlanStatus getStatus() {
        return status;
    }

    public TravelGenerationExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
