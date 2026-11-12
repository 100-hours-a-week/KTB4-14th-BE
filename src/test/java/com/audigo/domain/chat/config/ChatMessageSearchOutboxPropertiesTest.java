package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatMessageSearchOutboxPropertiesTest {
    private MockEnvironment enabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("audigo.chat.search-sync.record-enabled", "true")
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/audigo_search_sync_lab?serverTimezone=UTC")
                .withProperty("audigo.chat.search-sync.target-generation", "sync-v1-test")
                .withProperty("audigo.chat.search-sync.target-index", "chat-messages-sync-v1-20261109-120000-001");
        env.setActiveProfiles("search-sync-lab");
        return env;
    }

    @Test void disabled_by_default_without_any_sync_configuration() {
        assertThat(new ChatMessageSearchOutboxProperties(new MockEnvironment()).recordEnabled()).isFalse();
    }
    @Test void accepts_explicit_isolated_sync_target() {
        assertThat(new ChatMessageSearchOutboxProperties(enabled()).recordEnabled()).isTrue();
    }
    @Test void rejects_non_sync_profile() {
        MockEnvironment env = enabled(); env.setActiveProfiles("prod");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }
    @Test void rejects_the_existing_fixture_database() {
        MockEnvironment env = enabled().withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13306/audigo_search_lab");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }
    @Test void rejects_remote_database() {
        MockEnvironment env = enabled().withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/audigo_search_sync_lab");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }
    @Test void rejects_existing_nfc_index() {
        MockEnvironment env = enabled().withProperty("audigo.chat.search-sync.target-index", "chat-messages-nfc-v1-20261108-113929-535");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }
    @Test void requires_a_generation() {
        MockEnvironment env = enabled().withProperty("audigo.chat.search-sync.target-generation", "");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }
    @Test void worker_only_still_requires_isolation() {
        var env=new MockEnvironment().withProperty("audigo.chat.search-sync.worker-enabled","true");
        assertThatIllegalArgumentException().isThrownBy(()->new ChatMessageSearchOutboxProperties(env));
    }
    @Test void lease_must_exceed_http_timeout_with_margin() {
        var env=enabled().withProperty("audigo.chat.search-sync.lease-seconds","3");
        assertThatIllegalArgumentException().isThrownBy(()->new ChatMessageSearchOutboxProperties(env));
    }
}
