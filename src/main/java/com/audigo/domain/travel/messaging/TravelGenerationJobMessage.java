package com.audigo.domain.travel.messaging;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelGenerationJobMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("generation_job_id") Long generationJobId,
        @JsonProperty("message_version") String messageVersion,
        @JsonProperty("occurred_at") Instant occurredAt,
        AiTravelGenerationRequest request
) {

    public TravelGenerationJobMessage {
        eventId = requireText(eventId, "이벤트 ID는 필수입니다.");
        generationJobId = Objects.requireNonNull(generationJobId, "여행 생성 작업 ID는 필수입니다.");
        messageVersion = requireText(messageVersion, "메시지 버전은 필수입니다.");
        occurredAt = Objects.requireNonNull(occurredAt, "메시지 발생 시각은 필수입니다.");
        request = Objects.requireNonNull(request, "AI 요청 스냅샷은 필수입니다.");
    }

    public static TravelGenerationJobMessage create(
            Long generationJobId,
            AiTravelGenerationRequest request
    ) {
        return new TravelGenerationJobMessage(
                UUID.randomUUID().toString(),
                generationJobId,
                TravelGenerationMessageVersion.V1,
                Instant.now(),
                request
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
