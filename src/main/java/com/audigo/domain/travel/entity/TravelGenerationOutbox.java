package com.audigo.domain.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "ai_generation_job_outbox_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ai_generation_outbox_event", columnNames = "event_id"),
                @UniqueConstraint(name = "uk_ai_generation_outbox_job", columnNames = "generation_job_id")
        },
        indexes = {
                @Index(name = "idx_ai_generation_outbox_pending", columnList = "status,next_attempt_at"),
                @Index(name = "idx_ai_generation_outbox_processing", columnList = "status,locked_at")
        }
)
public class TravelGenerationOutbox {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_job_id", nullable = false)
    private TravelGenerationJob job;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TravelGenerationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TravelGenerationOutbox() {
    }

    private TravelGenerationOutbox(
            TravelGenerationJob job,
            String eventId,
            String eventType,
            String payload
    ) {
        this.job = Objects.requireNonNull(job, "여행 생성 작업은 필수입니다.");
        this.eventId = requireText(eventId, "Outbox 이벤트 ID는 필수입니다.");
        this.eventType = requireText(eventType, "Outbox 이벤트 유형은 필수입니다.");
        this.payload = requireText(payload, "Outbox 메시지는 필수입니다.");
        this.status = TravelGenerationOutboxStatus.PENDING;
        this.nextAttemptAt = LocalDateTime.now();
    }

    public static TravelGenerationOutbox create(
            TravelGenerationJob job,
            String eventId,
            String eventType,
            String payload
    ) {
        return new TravelGenerationOutbox(job, eventId, eventType, payload);
    }

    public void claim(LocalDateTime now) {
        if (status == TravelGenerationOutboxStatus.PUBLISHED) {
            throw new IllegalStateException("이미 발행된 Outbox 이벤트입니다.");
        }
        status = TravelGenerationOutboxStatus.PROCESSING;
        lockedAt = Objects.requireNonNull(now, "선점 시각은 필수입니다.");
    }

    public void markPublished(LocalDateTime now) {
        requireProcessing();
        status = TravelGenerationOutboxStatus.PUBLISHED;
        publishedAt = Objects.requireNonNull(now, "발행 완료 시각은 필수입니다.");
        lockedAt = null;
        lastError = null;
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt, String error) {
        requireProcessing();
        status = TravelGenerationOutboxStatus.PENDING;
        attemptCount++;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "다음 재시도 시각은 필수입니다.");
        lockedAt = null;
        lastError = normalizeError(error);
    }

    private void requireProcessing() {
        if (status != TravelGenerationOutboxStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 Outbox 이벤트만 상태를 변경할 수 있습니다.");
        }
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return "SQS 발행에 실패했습니다.";
        }
        String normalized = error.trim();
        return normalized.length() > MAX_ERROR_LENGTH
                ? normalized.substring(0, MAX_ERROR_LENGTH)
                : normalized;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public TravelGenerationJob getJob() {
        return job;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public TravelGenerationOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
