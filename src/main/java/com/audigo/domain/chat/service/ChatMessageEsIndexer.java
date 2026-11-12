package com.audigo.domain.chat.service;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.config.ChatMessageSearchDeployment;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository.ClaimedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name="audigo.chat.search-sync.worker-enabled", havingValue="true")
public class ChatMessageEsIndexer {
    private final HttpClient client;
    private final URI base;
    private final String authorization;
    private final ChatMessageSearchOutboxProperties properties;
    private final ObjectMapper mapper;

    @Autowired
    public ChatMessageEsIndexer(ChatMessageSearchOutboxProperties properties, Environment environment, ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                ChatMessageSearchDeployment.base(environment, true),
                ChatMessageSearchDeployment.authorization(environment, true), properties, mapper);
    }

    // Explicit transport injection also supports disposable ES and fault-response integration tests.
    public ChatMessageEsIndexer(HttpClient client, URI base, String authorization,
                                ChatMessageSearchOutboxProperties properties, ObjectMapper mapper) {
        this.client=client; this.base=ChatMessageSearchDeployment.parseBase(base.toString());
        this.authorization=authorization; this.properties=properties; this.mapper=mapper;
    }

    public void index(ClaimedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ES indexing must run outside a DB transaction");
        if (!event.targetIndex().equals(properties.targetIndex()) || !event.targetGeneration().equals(properties.targetGeneration()))
            throw failure(false,"TARGET_MISMATCH");
        Map<String,Object> document = document(event);
        // Refuse missing/wrong mappings before PUT rather than intentionally creating a dynamic index.
        JsonNode mappings = request("/"+event.targetIndex()+"/_mapping", null);
        JsonNode meta = mappings.path(event.targetIndex()).path("mappings").path("_meta");
        if (!meta.path("text_contract").asText().equals("nfc-v1") ||
                !meta.path("sync_generation").asText().equals(event.targetGeneration())) throw failure(false,"INDEX_CONTRACT_MISMATCH");
        JsonNode result = request("/"+event.targetIndex()+"/_doc/"+event.messageId()+"?refresh=false", document);
        if (!result.path("_index").asText().equals(event.targetIndex()) || !result.path("_id").asText().equals(Long.toString(event.messageId())) ||
                !(result.path("result").asText().equals("created") || result.path("result").asText().equals("updated")) ||
                !result.path("_shards").path("failed").isIntegralNumber() || result.path("_shards").path("failed").intValue()!=0)
            throw failure(true,"ES_INVALID_ACK");
    }

    private Map<String,Object> document(ClaimedEvent event) {
        try {
            JsonNode p=mapper.readTree(event.payload());
            if (!p.isObject() || p.size()!=8 || !p.path("schema_version").isIntegralNumber() || p.path("schema_version").intValue()!=1 ||
                    !p.path("event_type").asText().equals("CHAT_MESSAGE_CREATED") || !p.path("text_contract").asText().equals("nfc-v1") ||
                    positiveLong(p.path("message_id"))!=event.messageId() || !p.path("status").asText().equals("SENT") || !p.path("content").isTextual() ||
                    !p.path("sent_at").isTextual()) throw failure(false,"INVALID_PAYLOAD");
            String content=p.path("content").asText();
            if(content.isEmpty() || content.codePointCount(0,content.length())>500)throw failure(false,"INVALID_PAYLOAD");
            OffsetDateTime time=OffsetDateTime.parse(p.path("sent_at").asText());
            if(!time.getOffset().equals(ZoneOffset.UTC) || time.getNano()%1000!=0)throw failure(false,"INVALID_PAYLOAD");
            Map<String,Object> d=new LinkedHashMap<>();
            d.put("message_id",event.messageId()); d.put("room_id",positiveLong(p.path("room_id")));
            d.put("status","SENT"); d.put("sent_at",time.toInstant().toString());
            d.put("content",content); d.put("content_nfc",Normalizer.normalize(content,Normalizer.Form.NFC));
            return d;
        } catch (IndexingException exception) {throw exception;}
        catch (Exception exception) {throw failure(false,"INVALID_PAYLOAD");}
    }

    private long positiveLong(JsonNode value) {
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1)throw failure(false,"INVALID_PAYLOAD");
        return value.longValue();
    }

    private JsonNode request(String path,Map<String,Object> body) {
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()));
            if(authorization!=null&&!authorization.isEmpty())builder.header("Authorization",authorization);
            if(body==null)builder.GET();
            else builder.header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
            HttpResponse<String> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status=response.statusCode();
            if(status!=200&&status!=201)throw failure(status==429||status>=500,"ES_HTTP_"+status);
            try {
                JsonNode result=mapper.readTree(response.body());
                if(result==null||!result.isObject())throw failure(true,"ES_INVALID_RESPONSE");
                return result;
            }
            catch (Exception exception) {throw failure(true,"ES_INVALID_RESPONSE");}
        } catch (InterruptedException exception) {Thread.currentThread().interrupt();throw failure(true,"ES_INTERRUPTED");}
        catch (IOException exception) {throw failure(true,"ES_IO");}
    }

    private static IndexingException failure(boolean retryable,String code) {return new IndexingException(retryable,code);}
    public static class IndexingException extends RuntimeException {
        private final boolean retryable;
        public IndexingException(boolean retryable,String code) {super(code);this.retryable=retryable;}
        public boolean retryable() {return retryable;}
    }
}
