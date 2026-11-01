package com.audigo.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.notification.config.NotificationRealtimeProperties;
import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.domain.notification.service.NotificationSseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

class NotificationRealtimeRedisBridgeTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final NotificationRealtimeProperties properties = new NotificationRealtimeProperties("audigo-be", "test", "");

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void notification_types_reach_local_sse_on_both_servers_once(NotificationType type) throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationSseService serverA = mock(NotificationSseService.class);
        NotificationSseService serverB = mock(NotificationSseService.class);
        RedisNotificationRealtimeListener listenerA = new RedisNotificationRealtimeListener(objectMapper, serverA);
        RedisNotificationRealtimeListener listenerB = new RedisNotificationRealtimeListener(objectMapper, serverB);
        when(redisTemplate.convertAndSend(eq(properties.channel()), anyString())).thenAnswer(invocation -> {
            DefaultMessage message = message(invocation.getArgument(1));
            listenerA.onMessage(message, null);
            listenerB.onMessage(message, null);
            return 2L;
        });

        NotificationResponse notification = notification(type);
        new NotificationRealtimePublisher(redisTemplate, objectMapper, properties).publish(7L, notification);

        for (NotificationSseService server : new NotificationSseService[] {serverA, serverB}) {
            verify(server).sendNotification(7L, notification);
            verifyNoMoreInteractions(server);
        }
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(properties.channel()), json.capture());
        JsonNode event = objectMapper.readTree(json.getValue());
        assertThat(event.path("recipientUserId").asLong()).isEqualTo(7L);
        assertThat(event.path("eventType").asText()).isEqualTo("NOTIFICATION_CREATED");
        assertThat(event.path("payload").path("notification_id").asLong()).isEqualTo(123L);
        assertThat(event.path("payload").path("type").asText()).isEqualTo(type.name());
        assertThat(event.path("payload").path("created_at").asText()).isEqualTo("2026-11-01T10:00:00");
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "null", "{}",
            "{\"recipientUserId\":7,\"eventType\":\"UNSUPPORTED\",\"payload\":{}}",
            "{\"recipientUserId\":7,\"eventType\":\"NOTIFICATION_CREATED\",\"payload\":[]}",
            "{\"recipientUserId\":7,\"eventType\":\"NOTIFICATION_CREATED\",\"payload\":{}}"
    })
    void invalid_events_are_discarded_without_local_delivery(String json) {
        NotificationSseService localSse = mock(NotificationSseService.class);
        RedisNotificationRealtimeListener listener = new RedisNotificationRealtimeListener(objectMapper, localSse);

        assertThatCode(() -> listener.onMessage(message(json), null)).doesNotThrowAnyException();

        verifyNoInteractions(localSse);
    }

    @Test
    void invalid_recipient_or_payload_identifiers_are_discarded() {
        NotificationSseService localSse = mock(NotificationSseService.class);
        RedisNotificationRealtimeListener listener = new RedisNotificationRealtimeListener(objectMapper, localSse);
        ObjectNode event = objectMapper.valueToTree(new NotificationRealtimeRedisEvent(
                7L, NotificationRealtimeEventType.NOTIFICATION_CREATED,
                objectMapper.valueToTree(notification(NotificationType.NEW_MESSAGE))));

        event.put("recipientUserId", 0);
        listener.onMessage(message(event.toString()), null);
        event.put("recipientUserId", 7);
        ((ObjectNode) event.path("payload")).put("notification_id", -1);
        listener.onMessage(message(event.toString()), null);

        verifyNoInteractions(localSse);
    }

    @Test
    void redis_failure_does_not_trigger_a_direct_sse_fallback_or_escape() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        NotificationRealtimePublisher publisher = new NotificationRealtimePublisher(redisTemplate, objectMapper, properties);

        assertThatCode(() -> publisher.publish(7L, notification(NotificationType.NEW_MESSAGE)))
                .doesNotThrowAnyException();

        verify(redisTemplate).convertAndSend(eq(properties.channel()), anyString());
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    void one_local_delivery_failure_does_not_prevent_processing_the_next_event() throws Exception {
        NotificationSseService localSse = mock(NotificationSseService.class);
        NotificationResponse notification = notification(NotificationType.NEW_MESSAGE);
        doThrow(new IllegalStateException("disconnected")).doNothing()
                .when(localSse).sendNotification(7L, notification);
        RedisNotificationRealtimeListener listener = new RedisNotificationRealtimeListener(objectMapper, localSse);
        DefaultMessage event = message(objectMapper.writeValueAsString(new NotificationRealtimeRedisEvent(
                7L, NotificationRealtimeEventType.NOTIFICATION_CREATED, objectMapper.valueToTree(notification))));

        assertThatCode(() -> listener.onMessage(event, null)).doesNotThrowAnyException();
        listener.onMessage(event, null);

        verify(localSse, times(2)).sendNotification(7L, notification);
    }

    private DefaultMessage message(String json) {
        return new DefaultMessage(properties.channel().getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private NotificationResponse notification(NotificationType type) {
        NotificationTargetType targetType = switch (type) {
            case MATCH_SUCCESS -> NotificationTargetType.MATCH_CONNECTION;
            case NEW_MESSAGE -> NotificationTargetType.CHAT_ROOM;
            default -> NotificationTargetType.TRAVEL_PLAN;
        };
        return new NotificationResponse(123L, type, "알림", "새 알림", "새 알림", targetType, 54L,
                targetType == NotificationTargetType.TRAVEL_PLAN ? 54L : null,
                false, LocalDateTime.of(2026, 11, 1, 10, 0), null);
    }
}
