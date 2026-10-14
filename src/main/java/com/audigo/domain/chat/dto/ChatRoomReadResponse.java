package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ChatRoomReadResponse(
        @JsonProperty("chat_room_id") Long chatRoomId,
        @JsonProperty("last_read_chat_id") Long lastReadChatId,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("read_at") OffsetDateTime readAt
) {
}
