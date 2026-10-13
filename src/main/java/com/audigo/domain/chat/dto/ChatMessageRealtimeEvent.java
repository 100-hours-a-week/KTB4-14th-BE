package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatMessageRealtimeEvent(
        @JsonProperty("event_type") String eventType,
        ChatMessageResponse data
) {
    public static ChatMessageRealtimeEvent messageCreated(ChatMessageResponse data) {
        return new ChatMessageRealtimeEvent("MESSAGE_CREATED", data);
    }
}
