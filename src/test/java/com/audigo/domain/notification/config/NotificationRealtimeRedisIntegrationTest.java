package com.audigo.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
        private final NotificationSseService service = new NotificationSseService();
        private final List<SseEmitter> emitters = new ArrayList<>();

        @GetMapping(value = "/test/notifications/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe(@PathVariable Long userId) {
            SseEmitter emitter = service.subscribe(userId);
            emitters.add(emitter);
            return emitter;
        }
    }
}
