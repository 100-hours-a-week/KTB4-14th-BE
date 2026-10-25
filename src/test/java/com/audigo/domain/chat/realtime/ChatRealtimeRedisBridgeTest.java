package com.audigo.domain.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.config.ChatRealtimeProperties;
import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatMessageResponse;
import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.chat.dto.ChatReadReceiptRealtimeEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ChatRealtimeRedisBridgeTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final ChatRealtimeProperties properties = new ChatRealtimeProperties("audigo-be", "test", "");

    @Test
    void all_three_events_reach_local_delivery_on_both_servers_once() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SimpMessagingTemplate serverA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate serverB = mock(SimpMessagingTemplate.class);
        RedisChatRealtimeListener listenerA = new RedisChatRealtimeListener(
                objectMapper,
                new ChatLocalRealtimeDispatcher(serverA)
        );
        RedisChatRealtimeListener listenerB = new RedisChatRealtimeListener(
                objectMapper,
                new ChatLocalRealtimeDispatcher(serverB)
        );
        when(redisTemplate.convertAndSend(eq(properties.channel()), anyString())).thenAnswer(invocation -> {
            String json = invocation.getArgument(1);
            DefaultMessage redisMessage = new DefaultMessage(
                    properties.channel().getBytes(StandardCharsets.UTF_8),
                    json.getBytes(StandardCharsets.UTF_8)
            );
            listenerA.onMessage(redisMessage, null);
            listenerB.onMessage(redisMessage, null);
            return 2L;
        });

        ChatRealtimePublisher publisher = new ChatRealtimePublisher(redisTemplate, objectMapper, properties);
        ChatMessageRealtimeEvent message = ChatMessageRealtimeEvent.messageCreated(new ChatMessageResponse(
                91L, 12L, 3L, "client-message-1", 7L, "안녕하세요", OffsetDateTime.parse("2026-10-25T02:00:00Z")
        ));
        ChatReadReceiptRealtimeEvent readReceipt = ChatReadReceiptRealtimeEvent.updated(
                12L, 7L, 7L, OffsetDateTime.parse("2026-10-25T02:01:00Z")
        );
        ChatPresenceRealtimeEvent presence = ChatPresenceRealtimeEvent.statusChanged(7L, true);

        publisher.publish(7L, message);
        publisher.publish(7L, readReceipt);
        publisher.publish(7L, presence);

        for (SimpMessagingTemplate server : new SimpMessagingTemplate[] {serverA, serverB}) {
            verify(server).convertAndSendToUser("7", "/queue/chat/messages", message);
            verify(server).convertAndSendToUser("7", "/queue/chat/messages", readReceipt);
            verify(server).convertAndSendToUser("7", "/queue/chat/messages", presence);
        }

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, org.mockito.Mockito.times(3))
                .convertAndSend(eq(properties.channel()), jsonCaptor.capture());
        JsonNode publishedMessage = objectMapper.readTree(jsonCaptor.getAllValues().getFirst());
        assertThat(publishedMessage.path("recipientUserId").asLong()).isEqualTo(7L);
        assertThat(publishedMessage.path("eventType").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(publishedMessage.path("payload").path("event_type").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(publishedMessage.path("payload").path("data").path("message_seq").asLong()).isEqualTo(7L);
    }

    @Test
    void unsupported_event_type_is_not_delivered() {
        SimpMessagingTemplate localTemplate = mock(SimpMessagingTemplate.class);
        RedisChatRealtimeListener listener = new RedisChatRealtimeListener(
                objectMapper,
                new ChatLocalRealtimeDispatcher(localTemplate)
        );
        byte[] body = """
                {"recipientUserId":7,"eventType":"UNSUPPORTED","payload":{"event_type":"UNSUPPORTED"}}
                """.getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(properties.channel().getBytes(StandardCharsets.UTF_8), body), null);

        verifyNoInteractions(localTemplate);
    }

    @Test
    void redis_failure_does_not_throw_from_publisher() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(eq(properties.channel()), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        ChatRealtimePublisher publisher = new ChatRealtimePublisher(redisTemplate, objectMapper, properties);

        assertThatCode(() -> publisher.publish(7L, ChatPresenceRealtimeEvent.statusChanged(3L, true)))
                .doesNotThrowAnyException();
    }
}
