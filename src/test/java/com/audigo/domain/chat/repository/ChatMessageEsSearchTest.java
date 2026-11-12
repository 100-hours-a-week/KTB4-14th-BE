package com.audigo.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.config.ChatMessageSearchMode;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatMessageEsSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private ChatMessageEsSearch adapter;
    private static final String INDEX = "chat-messages-poc-v1-20261105-114517-601";
    private static final String HIT = """
            {"_index":"%s","_id":"9223372036854775806","_source":{"message_id":"9223372036854775806",
            "room_id":"54","status":"SENT","sent_at":"2026-01-01T00:00:00.000001Z"},
            "sort":["2026-01-01T00:00:00.000001Z",9223372036854775806]}
            """.formatted(INDEX);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        adapter = new ChatMessageEsSearch(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), INDEX, "Basic lab", mapper);
        response.set(envelope(HIT));
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }
    private String envelope(String hits) { return "{\"timed_out\":false,\"_shards\":{\"successful\":1,\"failed\":0},\"hits\":{\"hits\":[" + hits + "]}}"; }
    private void fails() {
        assertThatThrownBy(() -> adapter.search(new ChatRoomDetailQuery(54L, null, null, null), "검색", 51))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void server_constructor_uses_configured_endpoint_credentials_and_nfc_query() {
        var env = new MockEnvironment()
                .withProperty("audigo.chat.message-search-mode", "es")
                .withProperty("audigo.chat.es.text-contract", "nfc-v1")
                .withProperty("audigo.chat.es.url", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("audigo.chat.es.username", "reader")
                .withProperty("audigo.chat.es.password", "test-only")
                .withProperty("audigo.chat.es.index", INDEX)
                .withProperty("audigo.chat.search-sync.target-index", INDEX)
                .withProperty("audigo.chat.search-sync.target-generation", "test-v1")
                .withProperty("audigo.chat.search-sync.database-name", "audigo_stage")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal/audigo_stage");
        env.setActiveProfiles("search-server");
        adapter = new ChatMessageEsSearch(new ChatMessageSearchMode(env), env, mapper);
        assertThat(adapter.search(new ChatRoomDetailQuery(54L, null, null, null), "cafe\u0301", 51)).hasSize(1);
        assertThat(authorization.get()).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString("reader:test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(request.get().at("/query/bool/filter/2/match_phrase/content_nfc/query").asText()).isEqualTo("café");
    }

    @Test
    void nfc_search_uses_the_normalized_field_and_preserves_accents_sharp_s_and_literal_spacing() {
        adapter = new ChatMessageEsSearch(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), INDEX, "Basic lab", mapper, true);
        for (String[] pair : new String[][]{{"Cafe\u0301", "Café"}, {"가가", "가가"}, {"Straße", "Straße"}, {"cafe", "cafe"}, {"공항  이동!!", "공항  이동!!"}}) {
            adapter.search(new ChatRoomDetailQuery(54L, null, null, null), pair[0], 51);
            assertThat(request.get().at("/query/bool/filter/2/match_phrase/content_nfc/query").asText()).isEqualTo(pair[1]);
            assertThat(request.get().at("/query/bool/filter/2/match_phrase/content").isMissingNode()).isTrue();
        }
    }

    @Test
    void keeps_long_id_microseconds_literal_query_and_cursor() {
        var cursor = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 2000);
        var hits = adapter.search(new ChatRoomDetailQuery(54L, null, cursor, Long.MAX_VALUE), "*%", 51);
        assertThat(hits.getFirst().chatId()).isEqualTo(9223372036854775806L);
        assertThat(hits.getFirst().sentAt().getNano()).isEqualTo(1000);
        assertThat(request.get().path("search_after").get(1).longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(request.get().at("/query/bool/filter/2/match_phrase/content/query").asText()).isEqualTo("*%");
        assertThat(request.get().at("/query/bool/filter/0/term/room_id").longValue()).isEqualTo(54L);
        assertThat(request.get().at("/query/bool/filter/1/term/status").asText()).isEqualTo("SENT");
    }

    @Test
    void cursor_at_an_exact_minute_keeps_seconds_for_the_es_date_parser() {
        adapter.search(new ChatRoomDetailQuery(54L, null, LocalDateTime.of(2026, 1, 1, 0, 1), Long.MAX_VALUE), "공항 이동", 51);
        assertThat(request.get().path("search_after").get(0).asText()).isEqualTo("2026-01-01T00:01:00Z");
    }

    @Test
    void rejects_partial_timeout_malformed_and_http_payloads() {
        for (String body : new String[]{envelope(HIT).replace("\"failed\":0", "\"failed\":1"),
                envelope(HIT).replace("\"timed_out\":false", "\"timed_out\":true"), "{}", "not-json"}) {
            response.set(body); fails();
        }
    }

    @Test
    void rejects_cross_room_non_sent_duplicate_and_invalid_precision() {
        for (String hit : new String[]{HIT.replace("\"54\"", "\"55\""), HIT.replace("SENT", "FAILED"),
                HIT + "," + HIT, HIT.replace(".000001Z", ".000001001Z")}) {
            response.set(envelope(hit)); fails();
        }
    }

    @Test
    void refuses_http_inside_a_jdbc_transaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.search(new ChatRoomDetailQuery(54L, null, null, null), "검색", 51))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(request.get()).isNull();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
}
