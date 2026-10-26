package com.audigo.domain.chat.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ChatRealtimeRedisEvent(
        @JsonProperty("recipientUserId") Long recipientUserId,
        @JsonProperty("eventType") ChatRealtimeEventType eventType,
        @JsonProperty("payload") JsonNode payload
) {
}
