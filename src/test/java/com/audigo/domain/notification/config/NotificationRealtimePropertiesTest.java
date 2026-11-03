package com.audigo.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationRealtimePropertiesTest {

    @Test
    void default_channel_separates_applications_environments_and_chat() {
        assertThat(new NotificationRealtimeProperties("audigo-be", "local", "").channel())
                .isEqualTo("audigo:audigo-be:local:notification:realtime");
        assertThat(new NotificationRealtimeProperties("audigo-be", "production", null).channel())
                .isEqualTo("audigo:audigo-be:production:notification:realtime");
        assertThat(new NotificationRealtimeProperties("other-be", "local", "").channel())
                .isEqualTo("audigo:other-be:local:notification:realtime");
    }

    @Test
    void custom_channel_must_keep_application_environment_and_notification_namespace() {
        for (String channel : new String[] {
                "audigo:audigo-be:production:notification:realtime",
                "audigo:other-be:local:notification:realtime",
                "audigo:audigo-be:local:chat:realtime"
        }) {
            assertThatThrownBy(() -> new NotificationRealtimeProperties("audigo-be", "local", channel))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void accepts_a_custom_notification_channel_and_trims_configuration() {
        assertThat(new NotificationRealtimeProperties(
                " audigo-be ", " local ", " audigo:audigo-be:local:notification:custom "
        ).channel()).isEqualTo("audigo:audigo-be:local:notification:custom");
    }

    @Test
    void rejects_missing_application_or_environment() {
        assertThatThrownBy(() -> new NotificationRealtimeProperties(null, "local", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationRealtimeProperties("audigo-be", " ", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
