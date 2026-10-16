package com.audigo.domain.travel.messaging;

import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelGenerationResultMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("generation_job_id") Long generationJobId,
        @JsonProperty("message_version") String messageVersion,
        @JsonProperty("occurred_at") Instant occurredAt,
        TravelGenerationResultType type,
        TravelGenerationStage stage,
        TravelGenerationStageState state,
        JsonNode payload,
        @JsonProperty("error_message") String errorMessage
) {

    public TravelGenerationResultMessage {
        eventId = requireText(eventId, "이벤트 ID는 필수입니다.");
        generationJobId = Objects.requireNonNull(generationJobId, "여행 생성 작업 ID는 필수입니다.");
        messageVersion = requireText(messageVersion, "메시지 버전은 필수입니다.");
        occurredAt = Objects.requireNonNull(occurredAt, "메시지 발생 시각은 필수입니다.");
        type = Objects.requireNonNull(type, "결과 메시지 유형은 필수입니다.");
        validateContent(type, stage, state, payload, errorMessage);
    }

    private static void validateContent(
            TravelGenerationResultType type,
            TravelGenerationStage stage,
            TravelGenerationStageState state,
            JsonNode payload,
            String errorMessage
    ) {
        if (type == TravelGenerationResultType.PROGRESS && (stage == null || state == null)) {
            throw new IllegalArgumentException("진행 메시지에는 단계와 상태가 필요합니다.");
        }
        if (type == TravelGenerationResultType.COMPLETED && (payload == null || payload.isNull())) {
            throw new IllegalArgumentException("완료 메시지에는 AI 결과가 필요합니다.");
        }
        if (type == TravelGenerationResultType.FAILED
                && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("실패 메시지에는 오류 내용이 필요합니다.");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
