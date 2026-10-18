package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public record ChatMessageSearchResponse(
        List<Hit> matches,
        @JsonProperty("cursor_time") OffsetDateTime cursorTime,
        @JsonProperty("cursor_id") Long cursorId,
        @JsonProperty("has_next") boolean hasNext
) {
    public record Hit(@JsonProperty("chat_id") Long chatId, @JsonProperty("sent_at") OffsetDateTime sentAt) {
    }
}
