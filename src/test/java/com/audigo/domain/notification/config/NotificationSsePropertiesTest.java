package com.audigo.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationSsePropertiesTest {

    @Test
    void defaults_to_thirty_minutes_and_fifteen_second_heartbeats() {
        NotificationSseProperties properties = new NotificationSseProperties(null, null);
        assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void rejects_non_positive_or_sub_millisecond_values() {
        for (Duration value : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)}) {
            assertThatThrownBy(() -> new NotificationSseProperties(value, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NotificationSseProperties(Duration.ofMinutes(30), value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejects_heartbeats_that_cannot_run_before_timeout() {
        assertThatThrownBy(() -> new NotificationSseProperties(Duration.ofSeconds(10), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSseProperties(Duration.ofSeconds(10), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
