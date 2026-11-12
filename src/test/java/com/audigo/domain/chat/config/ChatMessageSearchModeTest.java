package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatMessageSearchModeTest {
    @Test
    void sync_search_accepts_only_the_same_nfc_write_target_and_database() {
        var env = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                .withProperty("audigo.chat.es.index", "chat-messages-sync-v1-20261109-120000-001")
                .withProperty("audigo.chat.search-sync.target-index", "chat-messages-sync-v1-20261109-120000-001")
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/audigo_search_sync_lab");
        env.setActiveProfiles("search-sync-lab");
        assertThat(new ChatMessageSearchMode(env).usesNfc()).isTrue();
        env.setProperty("audigo.chat.search-sync.target-index", "chat-messages-sync-v1-20261109-120000-002");
        assertThatThrownBy(() -> new ChatMessageSearchMode(env)).isInstanceOf(IllegalArgumentException.class);
        env.setProperty("audigo.chat.search-sync.target-index", "chat-messages-sync-v1-20261109-120000-001");
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13306/audigo_search_lab");
        assertThatThrownBy(() -> new ChatMessageSearchMode(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void original_lab_cannot_search_a_sync_index() {
        var env = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                .withProperty("audigo.chat.es.index", "chat-messages-sync-v1-20261109-120000-001");
        env.setActiveProfiles("search-lab");
        assertThatThrownBy(() -> new ChatMessageSearchMode(env)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void nfc_contract_requires_its_own_index_and_never_accepts_the_baseline_index() {
        var env = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                .withProperty("audigo.chat.es.index", "chat-messages-poc-v1-20261105-114517-601");
        env.setActiveProfiles("search-lab");
        assertThatThrownBy(() -> new ChatMessageSearchMode(env)).isInstanceOf(IllegalArgumentException.class);
        env.setProperty("audigo.chat.es.index", "chat-messages-nfc-v1-20261108-120000-001");
        assertThat(new ChatMessageSearchMode(env).usesNfc()).isTrue();
        env.setProperty("audigo.chat.es.text-contract", "baseline");
        assertThatThrownBy(() -> new ChatMessageSearchMode(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void default_retains_fulltext() {
        assertThat(new ChatMessageSearchMode(new MockEnvironment()).usesFulltext()).isTrue();
    }

    @Test
    void requires_local_lab_for_literal_control() {
        var environment = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "instr");
        assertThatThrownBy(() -> new ChatMessageSearchMode(environment)).isInstanceOf(IllegalArgumentException.class);
        environment.setActiveProfiles("search-lab");
        assertThat(new ChatMessageSearchMode(environment).usesFulltext()).isFalse();
    }

    @Test
    void rejects_misspelled_modes() {
        var environment = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "inst");
        environment.setActiveProfiles("search-lab");
        assertThatThrownBy(() -> new ChatMessageSearchMode(environment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void es_requires_lab_and_a_physical_index() {
        var environment = new MockEnvironment().withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.index", "chat-messages-poc-v1-20261105-114517-601");
        assertThatThrownBy(() -> new ChatMessageSearchMode(environment)).isInstanceOf(IllegalArgumentException.class);
        environment.setActiveProfiles("search-lab");
        assertThat(new ChatMessageSearchMode(environment).usesEs()).isTrue();
        environment.setProperty("audigo.chat.es.index", "unverified-index");
        assertThatThrownBy(() -> new ChatMessageSearchMode(environment)).isInstanceOf(IllegalArgumentException.class);
    }
}
