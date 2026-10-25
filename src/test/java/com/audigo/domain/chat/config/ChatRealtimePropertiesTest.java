package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChatRealtimePropertiesTest {

    @Test
    void default_channel_is_separated_by_application_and_environment() {
        ChatRealtimeProperties local = new ChatRealtimeProperties("audigo-be", "local", "");
        ChatRealtimeProperties production = new ChatRealtimeProperties("audigo-be", "production", "");

        assertThat(local.channel()).isEqualTo("audigo:audigo-be:local:chat:realtime");
        assertThat(production.channel()).isEqualTo("audigo:audigo-be:production:chat:realtime");
    }

    @Test
    void custom_channel_must_keep_application_and_environment_namespace() {
        assertThatThrownBy(() -> new ChatRealtimeProperties(
                "audigo-be", "production", "audigo:audigo-be:local:chat:realtime"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
