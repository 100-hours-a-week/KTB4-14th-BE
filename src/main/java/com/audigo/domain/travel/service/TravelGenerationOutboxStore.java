package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TravelOutboxProperties;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationOutbox;
import com.audigo.domain.travel.entity.TravelGenerationOutboxStatus;
import com.audigo.domain.travel.messaging.TravelGenerationJobMessage;
import com.audigo.domain.travel.repository.TravelGenerationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TravelGenerationOutboxStore {

    public static final String EVENT_TYPE = "TRAVEL_GENERATION_REQUESTED";

    private final TravelGenerationOutboxRepository outboxRepository;
    private final TravelOutboxProperties properties;
    private final ObjectMapper objectMapper;

    public TravelGenerationOutboxStore(
            TravelGenerationOutboxRepository outboxRepository,
            TravelOutboxProperties properties,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(TravelGenerationJob job, TravelGenerationJobMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            outboxRepository.save(TravelGenerationOutbox.create(
                    job,
                    message.eventId(),
                    EVENT_TYPE,
                    payload
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("여행 생성 Job 메시지를 직렬화할 수 없습니다.", exception);
        }
    }

    @Transactional
    public List<ClaimedEvent> claimReadyEvents() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiredAt = now.minusSeconds(properties.processingLeaseSeconds());
        List<TravelGenerationOutbox> events = outboxRepository.findReadyForPublish(
                TravelGenerationOutboxStatus.PENDING,
                TravelGenerationOutboxStatus.PROCESSING,
                now,
                leaseExpiredAt,
                PageRequest.of(0, properties.batchSize())
        );
        events.forEach(event -> event.claim(now));
        return events.stream()
                .map(event -> new ClaimedEvent(
                        event.getId(),
                        event.getEventId(),
                        event.getPayload(),
                        event.getAttemptCount()
                ))
                .toList();
    }

    @Transactional
    public void markPublished(Long eventId) {
        TravelGenerationOutbox event = getEvent(eventId);
        event.markPublished(LocalDateTime.now());
    }

    @Transactional
    public void markForRetry(Long eventId, String error) {
        TravelGenerationOutbox event = getEvent(eventId);
        long delaySeconds = retryDelaySeconds(event.getAttemptCount() + 1);
        event.scheduleRetry(LocalDateTime.now().plusSeconds(delaySeconds), error);
    }

    private long retryDelaySeconds(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long multiplier = 1L << exponent;
        long base = properties.retryBaseDelaySeconds();
        if (base > properties.retryMaxDelaySeconds() / multiplier) {
            return properties.retryMaxDelaySeconds();
        }
        return Math.min(base * multiplier, properties.retryMaxDelaySeconds());
    }

    private TravelGenerationOutbox getEvent(Long eventId) {
        return outboxRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox 이벤트를 찾을 수 없습니다."));
    }

    public record ClaimedEvent(
            Long id,
            String eventId,
            String payload,
            int attemptCount
    ) {
    }
}
