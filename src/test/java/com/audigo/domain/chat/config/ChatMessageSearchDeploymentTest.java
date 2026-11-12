package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

class ChatMessageSearchDeploymentTest {
    @Test void server_yaml_binds_cloud_environment_variables_without_enabling_lab_mode() throws Exception {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod", "search-server");
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("cloud-test", Map.ofEntries(
                Map.entry("AUDIGO_CHAT_MESSAGE_SEARCH_MODE", "es"),
                Map.entry("AUDIGO_CHAT_ES_URL", "https://es.search.svc:9200"),
                Map.entry("AUDIGO_CHAT_ES_USERNAME", "reader"),
                Map.entry("AUDIGO_CHAT_ES_PASSWORD", "test-reader-only"),
                Map.entry("AUDIGO_CHAT_ES_INDEX", "audigo-messages-v1"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_DATABASE_NAME", "audigo_stage"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_TARGET_GENERATION", "stage-v1"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_RECORD_ENABLED", "true"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_WORKER_ENABLED", "true"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_ES_USERNAME", "writer"),
                Map.entry("AUDIGO_CHAT_SEARCH_SYNC_ES_PASSWORD", "test-writer-only"))));
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.internal/audigo_stage");
        for (var source : new YamlPropertySourceLoader().load("server-yaml",
                new ClassPathResource("application-search-server.yml"))) env.getPropertySources().addLast(source);
        var mode = new ChatMessageSearchMode(env);
        var sync = new ChatMessageSearchOutboxProperties(env);
        assertThat(mode.isLab()).isFalse();
        assertThat(mode.usesNfc()).isTrue();
        assertThat(sync.recordEnabled()).isTrue();
        assertThat(sync.workerEnabled()).isTrue();
        assertThat(sync.targetIndex()).isEqualTo("audigo-messages-v1");
        assertThat(ChatMessageSearchDeployment.base(env, true)).isEqualTo(URI.create("https://es.search.svc:9200/"));
    }

    private MockEnvironment server() {
        var env = new MockEnvironment()
                .withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                .withProperty("audigo.chat.es.url", "https://elasticsearch.search.svc.cluster.local:9200")
                .withProperty("audigo.chat.es.username", "search-reader")
                .withProperty("audigo.chat.es.password", "test-reader-only")
                .withProperty("audigo.chat.es.index", "audigo-chat-messages-v1")
                .withProperty("spring.datasource.url", "jdbc:mysql://mysql.internal:3306/audigo_stage?serverTimezone=UTC")
                .withProperty("audigo.chat.search-sync.database-name", "audigo_stage")
                .withProperty("audigo.chat.search-sync.target-index", "audigo-chat-messages-v1")
                .withProperty("audigo.chat.search-sync.target-generation", "stage-v1");
        env.setActiveProfiles("prod", "search-server");
        return env;
    }

    @Test void accepts_server_dns_database_and_index_without_enabling_lab_headers_or_writes() {
        var env = server();
        var mode = new ChatMessageSearchMode(env);
        assertThat(mode.usesNfc()).isTrue();
        assertThat(mode.isLab()).isFalse();
        assertThat(new ChatMessageSearchOutboxProperties(env).recordEnabled()).isFalse();
        assertThat(ChatMessageSearchDeployment.base(env, false).getHost())
                .isEqualTo("elasticsearch.search.svc.cluster.local");
    }

    @Test void existing_server_profile_alone_does_not_enable_es() {
        var env = server(); env.setActiveProfiles("prod");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
    }

    @ParameterizedTest @ValueSource(strings = {"search-lab", "search-sync-lab"})
    void rejects_mixed_lab_and_server_profiles(String lab) {
        var env = server(); env.setActiveProfiles("search-server", lab);
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }

    @Test void disabled_server_feature_keeps_existing_default_without_requiring_es_settings() {
        var env = new MockEnvironment(); env.setActiveProfiles("search-server");
        assertThat(new ChatMessageSearchMode(env).usesFulltext()).isTrue();
        assertThat(new ChatMessageSearchOutboxProperties(env).workerEnabled()).isFalse();
    }

    @Test void server_instr_rollback_does_not_require_a_reader_but_can_keep_sync_running() {
        var env = server().withProperty("audigo.chat.message-search-mode", "instr")
                .withProperty("audigo.chat.es.password", "")
                .withProperty("audigo.chat.search-sync.record-enabled", "true")
                .withProperty("audigo.chat.search-sync.worker-enabled", "true")
                .withProperty("audigo.chat.search-sync.es-username", "index-writer")
                .withProperty("audigo.chat.search-sync.es-password", "test-writer-only");
        assertThat(new ChatMessageSearchMode(env).value()).isEqualTo("instr");
        assertThat(new ChatMessageSearchOutboxProperties(env).workerEnabled()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"", "audigo_other"})
    void rejects_missing_or_wrong_database_confirmation(String name) {
        var env = server().withProperty("audigo.chat.search-sync.database-name", name)
                .withProperty("audigo.chat.search-sync.record-enabled", "true");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }

    @ParameterizedTest @ValueSource(strings = {"audigo-*", "a,b", "a/b", "Uppercase", "_hidden", "../other"})
    void rejects_wildcards_multi_targets_and_unsafe_index_names(String index) {
        var env = server().withProperty("audigo.chat.es.index", index)
                .withProperty("audigo.chat.search-sync.target-index", index);
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
    }

    @Test void rejects_target_mismatch_oversized_index_missing_generation_and_non_nfc_contract() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(
                server().withProperty("audigo.chat.search-sync.target-index", "another-index")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(server()
                .withProperty("audigo.chat.es.index", "a".repeat(101))
                .withProperty("audigo.chat.search-sync.target-index", "a".repeat(101))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(
                server().withProperty("audigo.chat.search-sync.target-generation", "")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(
                server().withProperty("audigo.chat.es.text-contract", "baseline")));
    }

    @ParameterizedTest @ValueSource(strings = {"audigo.chat.es.url", "audigo.chat.es.username", "audigo.chat.es.password"})
    void server_reader_never_falls_back_to_local_connection_or_credentials(String key) {
        var env = server().withProperty(key, "");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
    }

    @Test void rejects_lab_password_on_server() {
        var env = server().withProperty("audigo.chat.es.password", "audigo-search-lab-es-only-password");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchMode(env));
    }

    @Test void enabled_worker_requires_its_own_credentials_and_same_endpoint() {
        var env = server().withProperty("audigo.chat.search-sync.worker-enabled", "true");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
        env.withProperty("audigo.chat.search-sync.es-username", "index-writer")
                .withProperty("audigo.chat.search-sync.es-password", "test-writer-only");
        assertThat(new ChatMessageSearchOutboxProperties(env).workerEnabled()).isTrue();
        assertThat(ChatMessageSearchDeployment.authorization(env, true))
                .isNotEqualTo(ChatMessageSearchDeployment.authorization(env, false));
        env.withProperty("audigo.chat.search-sync.es-url", "https://another-es.internal:9200");
        assertThatIllegalArgumentException().isThrownBy(() -> new ChatMessageSearchOutboxProperties(env));
    }

    @ParameterizedTest @ValueSource(strings = {"http://es.internal:9200", "https://es.internal:9200/"})
    void accepts_explicit_http_or_https_origin(String value) {
        assertThat(ChatMessageSearchDeployment.parseBase(value).getHost()).isEqualTo("es.internal");
    }

    @ParameterizedTest @ValueSource(strings = {"", "file:///tmp/es", "http://0.0.0.0:9200", "http://[::]:9200",
            "https://user:private-password@es.internal:9200", "http://es.internal:9200/path",
            "http://es.internal:9200?token=private-token", "http://es.internal:9200#fragment", "http://es.internal:0"})
    void rejects_invalid_origins_without_exposing_input(String value) {
        assertThatThrownBy(() -> ChatMessageSearchDeployment.parseBase(value))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause()
                .hasMessageNotContaining("private-password").hasMessageNotContaining("private-token");
    }

    @Test void local_connection_guards_remain_in_effect() {
        var env = new MockEnvironment().withProperty("audigo.chat.es.url", "https://es.internal:9200");
        env.setActiveProfiles("search-lab");
        assertThatIllegalArgumentException().isThrownBy(() -> ChatMessageSearchDeployment.base(env, false));
        env.withProperty("audigo.chat.es.url", "http://127.0.0.1:19200");
        assertThat(ChatMessageSearchDeployment.base(env, false)).isEqualTo(URI.create("http://127.0.0.1:19200"));
        env.withProperty("audigo.chat.search-sync.es-url", "http://es.internal:9200");
        assertThatIllegalArgumentException().isThrownBy(() -> ChatMessageSearchDeployment.base(env, true));
    }
}
