package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatPresenceRegistryTest {

    private final ChatPresenceRegistry registry = new ChatPresenceRegistry();

    @Test
    void user_remains_online_until_the_last_active_session_disconnects() {
        assertThat(registry.connected(42L, "session-1")).isTrue();
        assertThat(registry.connected(42L, "session-2")).isFalse();

        assertThat(registry.isOnline(42L)).isTrue();

        assertThat(registry.disconnected(42L, "session-1")).isFalse();
        assertThat(registry.isOnline(42L)).isTrue();

        assertThat(registry.disconnected(42L, "session-2")).isTrue();
        assertThat(registry.isOnline(42L)).isFalse();
    }

    @Test
    void disconnecting_unknown_or_already_removed_session_keeps_user_offline() {
        assertThat(registry.disconnected(42L, "unknown-session")).isFalse();

        assertThat(registry.isOnline(42L)).isFalse();
    }
}
