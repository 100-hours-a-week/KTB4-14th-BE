package com.audigo.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.domain.notification.realtime.NotificationRealtimePublisher;
import com.audigo.domain.notification.realtime.RedisNotificationRealtimeListener;
import com.audigo.domain.notification.service.NotificationSseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class NotificationRealtimeRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine")
    ).withExposedPorts(6379);

    @Test
    void pubsub_disconnect_triggers_resync_after_confirmed_resubscription_and_delivery_recovers() throws Exception {
        LettuceConnectionFactory connectionA = connectionFactory();
        LettuceConnectionFactory connectionB = connectionFactory();
        RedisMessageListenerContainer containerA = null;
        RedisMessageListenerContainer containerB = null;
        SseController controllerA = new SseController();
        SseController controllerB = new SseController();
        try {
            ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
            NotificationRealtimeProperties properties = new NotificationRealtimeProperties("audigo-be", "recovery", "");
            NotificationRealtimeRedisConfig config = new NotificationRealtimeRedisConfig();
            RedisNotificationRealtimeListener listenerA = spy(new RedisNotificationRealtimeListener(objectMapper, controllerA.service));
            RedisNotificationRealtimeListener listenerB = spy(new RedisNotificationRealtimeListener(objectMapper, controllerB.service));
            containerA = config.notificationRealtimeRedisMessageListenerContainer(connectionA, listenerA, properties);
            containerB = config.notificationRealtimeRedisMessageListenerContainer(connectionB, listenerB, properties);
            containerA.afterPropertiesSet();
            containerB.afterPropertiesSet();
            containerA.start();
            containerB.start();
            verify(listenerA, timeout(5_000)).onChannelSubscribed(any(byte[].class), anyLong());
            verify(listenerB, timeout(5_000)).onChannelSubscribed(any(byte[].class), anyLong());
            MvcResult streamA = subscribe(MockMvcBuilders.standaloneSetup(controllerA).build(), 7L);
            MvcResult streamB = subscribe(MockMvcBuilders.standaloneSetup(controllerB).build(), 7L);
            controllerA.service.maintainConnections();
            controllerB.service.maintainConnections();
            int resyncBeforeA = resyncCount(streamA);
            int resyncBeforeB = resyncCount(streamB);
            clearInvocations(listenerA, listenerB);

            // Kill only the test Redis Pub/Sub connections, keeping HTTP SSE streams and Redis itself alive.
            var killed = redis.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "PUBSUB");
            assertThat(killed.getExitCode()).isZero();
            assertThat(Integer.parseInt(killed.getStdout().trim())).isGreaterThanOrEqualTo(2);
            verify(listenerA, timeout(10_000).atLeastOnce()).onChannelSubscribed(any(byte[].class), anyLong());
            verify(listenerB, timeout(10_000).atLeastOnce()).onChannelSubscribed(any(byte[].class), anyLong());
            controllerA.service.maintainConnections();
            controllerB.service.maintainConnections();

            assertThat(resyncCount(streamA)).isEqualTo(resyncBeforeA + 1);
            assertThat(resyncCount(streamB)).isEqualTo(resyncBeforeB + 1);
            assertThat(streamA.getRequest().isAsyncStarted()).isTrue();
            assertThat(streamB.getRequest().isAsyncStarted()).isTrue();
            StringRedisTemplate template = new StringRedisTemplate(connectionA);
            new NotificationRealtimePublisher(template, objectMapper, properties).publish(7L,
                    new NotificationResponse(456L, NotificationType.NEW_MESSAGE, "새 채팅", "복구 후", "복구 후",
                            NotificationTargetType.CHAT_ROOM, 54L, null, false, LocalDateTime.now(), null));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(streamA.getResponse().getContentAsString()).contains("\"notification_id\":456");
                assertThat(streamB.getResponse().getContentAsString()).contains("\"notification_id\":456");
            });
        } finally {
            if (containerA != null) {
                containerA.destroy();
            }
            if (containerB != null) {
                containerB.destroy();
            }
            controllerA.emitters.forEach(SseEmitter::complete);
            controllerB.emitters.forEach(SseEmitter::complete);
            connectionA.destroy();
            connectionB.destroy();
        }
    }

    private int resyncCount(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().split("event:resync_required", -1).length - 1;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void either_server_publishes_to_both_servers_and_only_the_recipients_tabs(boolean publishFromA) throws Exception {
        LettuceConnectionFactory connectionA = connectionFactory();
        LettuceConnectionFactory connectionB = connectionFactory();
        RedisMessageListenerContainer containerA = null;
        RedisMessageListenerContainer containerB = null;
        SseController controllerA = new SseController();
        SseController controllerB = new SseController();
        try {
            ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
            NotificationRealtimeProperties properties = new NotificationRealtimeProperties("audigo-be", "integration", "");
            NotificationRealtimeRedisConfig config = new NotificationRealtimeRedisConfig();
            containerA = config.notificationRealtimeRedisMessageListenerContainer(
                    connectionA, new RedisNotificationRealtimeListener(objectMapper, controllerA.service), properties);
            containerB = config.notificationRealtimeRedisMessageListenerContainer(
                    connectionB, new RedisNotificationRealtimeListener(objectMapper, controllerB.service), properties);
            containerA.afterPropertiesSet();
            containerB.afterPropertiesSet();
            containerA.start();
            containerB.start();

            MockMvc serverA = MockMvcBuilders.standaloneSetup(controllerA).build();
            MockMvc serverB = MockMvcBuilders.standaloneSetup(controllerB).build();
            List<MvcResult> recipientTabs = List.of(subscribe(serverA, 7L), subscribe(serverA, 7L), subscribe(serverB, 7L));
            List<MvcResult> otherUsers = List.of(subscribe(serverA, 8L), subscribe(serverB, 8L));
            StringRedisTemplate template = new StringRedisTemplate(publishFromA ? connectionA : connectionB);
            NotificationRealtimePublisher publisher = new NotificationRealtimePublisher(template, objectMapper, properties);
            NotificationResponse notification = new NotificationResponse(123L, NotificationType.NEW_MESSAGE,
                    "새 채팅", "새 메시지", "새 메시지", NotificationTargetType.CHAT_ROOM, 54L, null,
                    false, LocalDateTime.of(2026, 11, 1, 10, 0), null);

            publisher.publish(7L, notification);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                for (MvcResult tab : recipientTabs) {
                    String body = tab.getResponse().getContentAsString();
                    assertThat(body).contains("event:connected", "\"notification_id\":123", "\"type\":\"NEW_MESSAGE\"");
                    assertThat(body.split("event:notification", -1)).hasSize(2);
                }
                for (MvcResult other : otherUsers) {
                    assertThat(other.getResponse().getContentAsString())
                            .contains("event:connected").doesNotContain("event:notification", "\"notification_id\":123");
                }
            });
        } finally {
            if (containerA != null) {
                containerA.destroy();
            }
            if (containerB != null) {
                containerB.destroy();
            }
            controllerA.emitters.forEach(SseEmitter::complete);
            controllerB.emitters.forEach(SseEmitter::complete);
            connectionA.destroy();
            connectionB.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    private MvcResult subscribe(MockMvc server, Long userId) throws Exception {
        return server.perform(get("/test/notifications/{userId}", userId).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }

    // Only a test endpoint: production subscriptions obtain the user ID from the authenticated session.
    @RestController
    static class SseController {
        private final NotificationSseService service = new NotificationSseService(new NotificationSseProperties(null, null));
        private final List<SseEmitter> emitters = new ArrayList<>();

        @GetMapping(value = "/test/notifications/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe(@PathVariable Long userId) {
            SseEmitter emitter = service.subscribe(userId);
            emitters.add(emitter);
            return emitter;
        }
    }
}
