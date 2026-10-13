package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatMessageCatchUpResponse(
        List<ChatMessageResponse> messages,
        int size,
        @JsonProperty("next_after_message_seq") long nextAfterMessageSequence,
        @JsonProperty("has_next") boolean hasNext
) {
    public ChatMessageCatchUpResponse {
        messages = List.copyOf(messages);
    }
}
