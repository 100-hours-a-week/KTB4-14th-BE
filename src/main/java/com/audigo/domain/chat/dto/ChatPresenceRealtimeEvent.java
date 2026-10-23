package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatPresenceRealtimeEvent(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("is_online") boolean online
) {
    public static ChatPresenceRealtimeEvent statusChanged(Long userId, boolean online) {
        return new ChatPresenceRealtimeEvent("PRESENCE_UPDATED", userId, online);
    }
}
