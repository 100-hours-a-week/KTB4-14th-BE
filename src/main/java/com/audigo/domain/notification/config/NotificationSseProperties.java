package com.audigo.domain.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.notification.sse")
public record NotificationSseProperties(Duration timeout, Duration heartbeatInterval) {

    public NotificationSseProperties {
        timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(15) : heartbeatInterval;
        if (timeout.toMillis() <= 0 || heartbeatInterval.toMillis() <= 0) {
            throw new IllegalArgumentException("Notification SSE timeout and heartbeat interval must be positive milliseconds");
        }
        if (heartbeatInterval.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("Notification SSE heartbeat interval must be shorter than timeout");
        }
    }
}
