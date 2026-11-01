package com.audigo.domain.notification.realtime;

import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.service.NotificationSseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class RedisNotificationRealtimeListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisNotificationRealtimeListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationSseService localSseService;

    public RedisNotificationRealtimeListener(ObjectMapper objectMapper, NotificationSseService localSseService) {
        this.objectMapper = objectMapper;
        this.localSseService = localSseService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        NotificationRealtimeRedisEvent event = null;
        NotificationResponse notification = null;
        try {
            event = objectMapper.readValue(message.getBody(), NotificationRealtimeRedisEvent.class);
            if (event == null || !positive(event.recipientUserId())
                    || event.eventType() != NotificationRealtimeEventType.NOTIFICATION_CREATED
                    || event.payload() == null || !event.payload().isObject()) {
                throw new IllegalArgumentException("Invalid notification realtime event envelope");
            }
            notification = objectMapper.treeToValue(event.payload(), NotificationResponse.class);
            if (!positive(notification.notificationId()) || notification.type() == null
                    || notification.title() == null || notification.content() == null
                    || notification.targetType() == null || !positive(notification.targetId())
                    || notification.createdAt() == null) {
                throw new IllegalArgumentException("Invalid NOTIFICATION_CREATED payload");
            }
            // Delivery stays local; a received event must never be published back to Redis.
            localSseService.sendNotification(event.recipientUserId(), notification);
            log.debug("notification_realtime_local_dispatched recipient_id={} notification_id={}",
                    event.recipientUserId(), notification.notificationId());
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("notification_realtime_redis_event_invalid", exception);
        } catch (RuntimeException exception) {
            log.warn("notification_realtime_local_delivery_failed recipient_id={} notification_id={}",
                    event == null ? null : event.recipientUserId(),
                    notification == null ? null : notification.notificationId(),
                    exception);
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
