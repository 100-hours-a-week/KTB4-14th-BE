package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.config.ChatMessageSearchMode;
import com.audigo.domain.chat.config.ChatMessageSearchDeployment;
import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** ES search adapter. No silent fallback on ES failure or empty hits. */
@Component
@ConditionalOnProperty(name = "audigo.chat.message-search-mode", havingValue = "es")
public class ChatMessageEsSearch {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageEsSearch.class);
    private final HttpClient client;
    private final URI endpoint;
    private final String index;
    private final String authorization;
    private final ObjectMapper mapper;
    private final boolean nfc;

    @Autowired
    public ChatMessageEsSearch(ChatMessageSearchMode mode, Environment environment, ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                ChatMessageSearchDeployment.base(environment, false), mode.esIndex(),
                ChatMessageSearchDeployment.authorization(environment, false), mapper, mode.usesNfc());
        if ((!mode.isLab() && !ChatMessageSearchDeployment.isServer(environment)) || !mode.usesEs()) {
            throw new IllegalArgumentException("ES requires a search lab or search-server ES mode");
        }
    }

    ChatMessageEsSearch(HttpClient client, URI base, String index, String authorization, ObjectMapper mapper) {
        this(client, base, index, authorization, mapper, false);
    }

    ChatMessageEsSearch(HttpClient client, URI base, String index, String authorization, ObjectMapper mapper, boolean nfc) {
        this.client = client; this.index = index; this.authorization = authorization; this.mapper = mapper;
        this.nfc = nfc;
        this.endpoint = base.resolve("/" + index + "/_search?allow_partial_search_results=false");
    }

    public List<ChatMessageSearchRepository.Hit> search(ChatRoomDetailQuery query, String keyword, int limit) {
        // This also catches a future caller accidentally keeping a JDBC transaction open.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ES HTTP must run outside the JDBC transaction");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("size", limit); body.put("track_total_hits", false);
            body.put("_source", List.of("message_id", "room_id", "status", "sent_at"));
            body.put("query", Map.of("bool", Map.of("filter", List.of(
                    Map.of("term", Map.of("room_id", query.roomId())),
                    Map.of("term", Map.of("status", "SENT")),
                    Map.of("match_phrase", Map.of(nfc ? "content_nfc" : "content", Map.of("query",
                            nfc ? Normalizer.normalize(keyword, Normalizer.Form.NFC) : keyword, "slop", 0)))))));
            body.put("sort", List.of(Map.of("sent_at", Map.of("order", "desc", "format", "strict_date_optional_time_nanos")),
                    Map.of("message_id", "desc")));
            // Instant includes seconds even at an exact minute; OffsetDateTime.toString() can omit them.
            if (query.cursorTime() != null) body.put("search_after", List.of(query.cursorTime().toInstant(ZoneOffset.UTC).toString(), query.cursorId()));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("ES HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (!root.path("timed_out").isBoolean() || root.path("timed_out").booleanValue() ||
                    !root.path("_shards").path("failed").isIntegralNumber() || root.path("_shards").path("failed").intValue() != 0 ||
                    root.path("_shards").path("successful").asInt() < 1) throw new IllegalStateException("ES timeout or partial shards");
            JsonNode rows = root.path("hits").path("hits");
            if (!rows.isArray() || rows.size() > limit) throw new IllegalStateException("Invalid ES hit count");
            List<ChatMessageSearchRepository.Hit> hits = new ArrayList<>();
            LocalDateTime previousTime = query.cursorTime(); Long previousId = query.cursorId();
            for (JsonNode row : rows) {
                JsonNode source = row.path("_source"), sort = row.path("sort");
                long messageId = positiveLong(source.path("message_id"));
                OffsetDateTime sentAt = OffsetDateTime.parse(source.path("sent_at").asText()).withOffsetSameInstant(ZoneOffset.UTC);
                if (!row.path("_index").asText().equals(index) || !row.path("_id").asText().equals(Long.toString(messageId)) ||
                        positiveLong(source.path("room_id")) != query.roomId() || !source.path("status").asText().equals("SENT") ||
                        sentAt.getNano() % 1000 != 0 || !sort.isArray() || sort.size() != 2 ||
                        !OffsetDateTime.parse(sort.get(0).asText()).toInstant().equals(sentAt.toInstant()) ||
                        positiveLong(sort.get(1)) != messageId) throw new IllegalStateException("Invalid ES scope or sort fields");
                LocalDateTime time = sentAt.toLocalDateTime();
                if (previousTime != null && (time.isAfter(previousTime) || (time.equals(previousTime) && messageId >= previousId))) {
                    throw new IllegalStateException("Invalid ES ordering or cursor boundary");
                }
                hits.add(new ChatMessageSearchRepository.Hit(messageId, time)); previousTime = time; previousId = messageId;
            }
            return List.copyOf(hits);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (Exception exception) {
            // IllegalStateException messages above are fixed diagnostic labels, never response bodies or keywords.
            String reason = exception instanceof IllegalStateException ? exception.getMessage() : exception.getClass().getSimpleName();
            log.warn("chat_message_es_search_failed index={} type={} reason={}", index, exception.getClass().getSimpleName(), reason);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static long positiveLong(JsonNode node) {
        if (!(node.isTextual() || (node.isIntegralNumber() && node.canConvertToLong()))) throw new IllegalArgumentException("Invalid ES long");
        long value = Long.parseLong(node.asText());
        if (value <= 0) throw new IllegalArgumentException("Invalid ES positive long");
        return value;
    }
}
