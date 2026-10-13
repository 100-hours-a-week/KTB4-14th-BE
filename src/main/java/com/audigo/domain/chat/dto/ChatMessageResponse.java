package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ChatMessageResponse(
        @JsonProperty("message_id") Long messageId,
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("sender_id") Long senderId,
        @JsonProperty("client_message_id") String clientMessageId,
        @JsonProperty("message_seq") long messageSequence,
        String content,
        @JsonProperty("sent_at") OffsetDateTime sentAt
) {
}
