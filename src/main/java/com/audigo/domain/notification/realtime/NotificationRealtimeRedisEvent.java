package com.audigo.domain.notification.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record NotificationRealtimeRedisEvent(
        @JsonProperty("recipientUserId") Long recipientUserId,
        @JsonProperty("eventType") NotificationRealtimeEventType eventType,
        @JsonProperty("payload") JsonNode payload
) {
}
