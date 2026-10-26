package com.audigo.domain.chat.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatMessageResponse;
import com.audigo.domain.chat.realtime.ChatLocalRealtimeDispatcher;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.chat.realtime.RedisChatRealtimeListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ChatRealtimeRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine")
    ).withExposedPorts(6379);

    @Test
    void one_publication_reaches_both_server_subscriptions() throws Exception {
        LettuceConnectionFactory connectionFactoryA = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory connectionFactoryB = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactoryA.afterPropertiesSet();
        connectionFactoryB.afterPropertiesSet();

        RedisMessageListenerContainer containerA = null;
        RedisMessageListenerContainer containerB = null;
        try {
            ObjectMapper objectMapper = JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
            ChatRealtimeProperties properties = new ChatRealtimeProperties("audigo-be", "integration", "");
            SimpMessagingTemplate serverA = mock(SimpMessagingTemplate.class);
            SimpMessagingTemplate serverB = mock(SimpMessagingTemplate.class);
            ChatRealtimeRedisConfig config = new ChatRealtimeRedisConfig();
            containerA = config.chatRealtimeRedisMessageListenerContainer(
                    connectionFactoryA,
                    new RedisChatRealtimeListener(objectMapper, new ChatLocalRealtimeDispatcher(serverA)),
                    properties
            );
            containerB = config.chatRealtimeRedisMessageListenerContainer(
                    connectionFactoryB,
                    new RedisChatRealtimeListener(objectMapper, new ChatLocalRealtimeDispatcher(serverB)),
                    properties
            );
            containerA.afterPropertiesSet();
            containerB.afterPropertiesSet();
            containerA.start();
            containerB.start();

            StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactoryA);
            redisTemplate.afterPropertiesSet();
            ChatRealtimePublisher publisher = new ChatRealtimePublisher(redisTemplate, objectMapper, properties);
            ChatMessageRealtimeEvent event = ChatMessageRealtimeEvent.messageCreated(new ChatMessageResponse(
                    91L, 12L, 3L, "client-message-1", 7L, "안녕하세요", OffsetDateTime.parse("2026-10-25T02:00:00Z")
            ));

            publisher.publish(7L, event);

            verify(serverA, timeout(5_000).times(1)).convertAndSendToUser("7", "/queue/chat/messages", event);
            verify(serverB, timeout(5_000).times(1)).convertAndSendToUser("7", "/queue/chat/messages", event);
        } finally {
            if (containerA != null) {
                containerA.destroy();
            }
            if (containerB != null) {
                containerB.destroy();
            }
            connectionFactoryA.destroy();
            connectionFactoryB.destroy();
        }
    }
}
