package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.*;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository.ClaimedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatMessageEsIndexerConfigurationTest {
    @Test void server_constructor_uses_writer_credentials_and_preserves_mapping_generation_and_nfc() throws Exception {
        var mapper = new ObjectMapper();
        var document = new AtomicReference<JsonNode>();
        List<String> authorization = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            paths.add(exchange.getRequestURI().toString());
            String response;
            if (exchange.getRequestMethod().equals("GET")) {
                response = "{\"audigo-messages-v1\":{\"mappings\":{\"_meta\":{\"text_contract\":\"nfc-v1\",\"sync_generation\":\"stage-v1\"}}}}";
            } else {
                document.set(mapper.readTree(exchange.getRequestBody()));
                response = "{\"_index\":\"audigo-messages-v1\",\"_id\":\"42\",\"result\":\"created\",\"_shards\":{\"failed\":0}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("audigo.chat.es.url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("audigo.chat.es.index", "audigo-messages-v1")
                    .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                    .withProperty("audigo.chat.search-sync.target-index", "audigo-messages-v1")
                    .withProperty("audigo.chat.search-sync.target-generation", "stage-v1")
                    .withProperty("audigo.chat.search-sync.database-name", "audigo_stage")
                    .withProperty("spring.datasource.url", "jdbc:mysql://db.internal/audigo_stage")
                    .withProperty("audigo.chat.search-sync.worker-enabled", "true")
                    .withProperty("audigo.chat.search-sync.es-username", "writer")
                    .withProperty("audigo.chat.search-sync.es-password", "test-writer-only");
            env.setActiveProfiles("search-server");
            var properties = new ChatMessageSearchOutboxProperties(env);
            var indexer = new ChatMessageEsIndexer(properties, env, mapper);
            var event = new ClaimedEvent(1, "test-event", 42, """
                    {"schema_version":1,"event_type":"CHAT_MESSAGE_CREATED","text_contract":"nfc-v1",
                    "message_id":42,"room_id":5,"status":"SENT","sent_at":"2026-11-12T00:00:00.123456Z","content":"cafe\u0301"}
                    """, "stage-v1", "audigo-messages-v1", "test-lease", 1);
            indexer.index(event);
            assertThat(paths).containsExactly("/audigo-messages-v1/_mapping", "/audigo-messages-v1/_doc/42?refresh=false");
            String expected = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("writer:test-writer-only".getBytes(StandardCharsets.UTF_8));
            assertThat(authorization).containsExactly(expected, expected);
            assertThat(document.get().path("content").asText()).isEqualTo("cafe\u0301");
            assertThat(document.get().path("content_nfc").asText()).isEqualTo("café");
            assertThat(document.get().path("sent_at").asText()).isEqualTo("2026-11-12T00:00:00.123456Z");
            assertThatThrownBy(() -> indexer.index(new ClaimedEvent(1, "test-event", 42, event.payload(),
                    "other-generation", "audigo-messages-v1", "test-lease", 1)))
                    .isInstanceOf(ChatMessageEsIndexer.IndexingException.class).hasMessage("TARGET_MISMATCH");
            assertThat(paths).hasSize(2);
        } finally {
            server.stop(0);
        }
    }
}
