package com.audigo.domain.chat.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/** Explicit JDBC persistence keeps the disabled feature out of existing JPA schema validation. */
public record ChatMessageSearchOutbox(
        Long messageId, String eventId, String targetGeneration, String targetIndex,
        String payload, LocalDateTime createdAt
) {
    public static final String EVENT_TYPE = "CHAT_MESSAGE_CREATED";
    public static final String TEXT_CONTRACT = "nfc-v1";

    public ChatMessageSearchOutbox {
        if (messageId == null || messageId < 1) throw new IllegalArgumentException("Persisted message ID required");
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(targetGeneration);
        Objects.requireNonNull(targetIndex);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(createdAt);
    }

    public static ChatMessageSearchOutbox create(Long messageId, String eventId, String generation,
                                                String index, String payload, LocalDateTime now) {
        return new ChatMessageSearchOutbox(messageId, eventId, generation, index, payload, now);
    }
}
