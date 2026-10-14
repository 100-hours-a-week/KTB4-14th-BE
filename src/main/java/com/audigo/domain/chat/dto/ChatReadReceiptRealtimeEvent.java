package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ChatReadReceiptRealtimeEvent(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("reader_id") Long readerId,
        @JsonProperty("read_through_message_seq") long readThroughMessageSequence,
        @JsonProperty("read_at") OffsetDateTime readAt
) {
    public static ChatReadReceiptRealtimeEvent updated(
            Long roomId,
            Long readerId,
            long readThroughMessageSequence,
            OffsetDateTime readAt
    ) {
        return new ChatReadReceiptRealtimeEvent(
                "READ_RECEIPT_UPDATED",
                roomId,
                readerId,
                readThroughMessageSequence,
                readAt
        );
    }
}
