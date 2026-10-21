package com.audigo.domain.travel.entity;

import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.messaging.TravelGenerationResultType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "ai_generation_result_inbox",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ai_generation_result_event", columnNames = "event_id"),
                @UniqueConstraint(name = "uk_ai_generation_result_sqs_message", columnNames = "sqs_message_id")
        },
        indexes = @Index(name = "idx_ai_generation_result_status_created", columnList = "status,created_at")
)
public class TravelGenerationResultInbox {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "sqs_message_id", nullable = false, length = 100)
    private String sqsMessageId;

    @Column(name = "generation_job_id")
    private Long generationJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 20)
    private TravelGenerationResultType messageType;

    @Column(name = "message_body", nullable = false, columnDefinition = "LONGTEXT")
    private String messageBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TravelGenerationResultInboxStatus status;

    @Column(name = "receive_count", nullable = false)
    private int receiveCount;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TravelGenerationResultInbox() {
    }

    private TravelGenerationResultInbox(
            String eventId,
            String sqsMessageId,
            Long generationJobId,
            TravelGenerationResultType messageType,
            String messageBody,
            TravelGenerationResultInboxStatus status,
            int receiveCount,
            String lastError
    ) {
        this.eventId = normalizeNullableText(eventId);
        this.sqsMessageId = requireText(sqsMessageId, "SQS 메시지 ID는 필수입니다.");
        this.generationJobId = generationJobId;
        this.messageType = messageType;
        this.messageBody = Objects.requireNonNull(messageBody, "SQS 메시지 원문은 필수입니다.");
        this.status = Objects.requireNonNull(status, "Inbox 상태는 필수입니다.");
        if (receiveCount < 1) {
            throw new IllegalArgumentException("SQS 수신 횟수는 1 이상이어야 합니다.");
        }
        this.receiveCount = receiveCount;
        this.lastError = normalizeError(lastError);
    }

    public static TravelGenerationResultInbox processing(
            TravelGenerationResultMessage message,
            String sqsMessageId,
            String messageBody,
            int receiveCount
    ) {
        Objects.requireNonNull(message, "여행 생성 결과 메시지는 필수입니다.");
        return new TravelGenerationResultInbox(
                message.eventId(),
                sqsMessageId,
                message.generationJobId(),
                message.type(),
                messageBody,
                TravelGenerationResultInboxStatus.PROCESSING,
                receiveCount,
                null
        );
    }

    public static TravelGenerationResultInbox failed(
            String sqsMessageId,
            String eventId,
            Long generationJobId,
            TravelGenerationResultType messageType,
            String messageBody,
            int receiveCount,
            String error
    ) {
        return new TravelGenerationResultInbox(
                eventId,
                sqsMessageId,
                generationJobId,
                messageType,
                messageBody,
                TravelGenerationResultInboxStatus.FAILED,
                receiveCount,
                error
        );
    }

    public void markProcessed() {
        if (status != TravelGenerationResultInboxStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 Inbox 메시지만 완료할 수 있습니다.");
        }
        status = TravelGenerationResultInboxStatus.PROCESSED;
        processedAt = LocalDateTime.now();
        lastError = null;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String normalizeNullableText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String normalized = error.trim();
        return normalized.length() > MAX_ERROR_LENGTH
                ? normalized.substring(0, MAX_ERROR_LENGTH)
                : normalized;
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

    public String getEventId() {
        return eventId;
    }

    public String getSqsMessageId() {
        return sqsMessageId;
    }

    public Long getGenerationJobId() {
        return generationJobId;
    }

    public TravelGenerationResultType getMessageType() {
        return messageType;
    }

    public String getMessageBody() {
        return messageBody;
    }

    public TravelGenerationResultInboxStatus getStatus() {
        return status;
    }

    public int getReceiveCount() {
        return receiveCount;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
