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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "ai_generation_job_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_generation_job_progress_job_stage",
                columnNames = {"generation_job_id", "stage"}
        )
)
public class TravelGenerationProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_job_id", nullable = false)
    private TravelGenerationJob job;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 30)
    private TravelGenerationStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TravelGenerationStageState state;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TravelGenerationProgress() {
    }

    private TravelGenerationProgress(TravelGenerationJob job, TravelGenerationStage stage) {
        this.job = Objects.requireNonNull(job, "여행 생성 작업은 필수입니다.");
        this.stage = Objects.requireNonNull(stage, "여행 생성 단계는 필수입니다.");
        this.state = TravelGenerationStageState.PENDING;
    }

    public static TravelGenerationProgress create(TravelGenerationJob job, TravelGenerationStage stage) {
        return new TravelGenerationProgress(job, stage);
    }

    public void update(TravelGenerationStageState state, String payload) {
        this.state = Objects.requireNonNull(state, "여행 생성 단계 상태는 필수입니다.");
        if (payload != null && !payload.isBlank()) {
            this.payload = payload;
        }
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

    public TravelGenerationJob getJob() {
        return job;
    }

    public TravelGenerationStage getStage() {
        return stage;
    }

    public TravelGenerationStageState getState() {
        return state;
    }

    public String getPayload() {
        return payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
