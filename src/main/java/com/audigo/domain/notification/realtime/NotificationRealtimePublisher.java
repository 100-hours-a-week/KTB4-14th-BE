package com.audigo.domain.notification.realtime;

import com.audigo.domain.notification.config.NotificationRealtimeProperties;
import com.audigo.domain.notification.dto.NotificationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationRealtimePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationRealtimeProperties properties;

    public NotificationRealtimePublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NotificationRealtimeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(Long recipientUserId, NotificationResponse notification) {
        try {
            NotificationRealtimeRedisEvent event = new NotificationRealtimeRedisEvent(
                    recipientUserId,
                    NotificationRealtimeEventType.NOTIFICATION_CREATED,
                    objectMapper.valueToTree(notification)
            );
            String json = objectMapper.writeValueAsString(event);
            Long subscribers = redisTemplate.convertAndSend(properties.channel(), json);
            log.info("notification_realtime_redis_published recipient_id={} notification_id={} channel={} subscribers={}",
                    recipientUserId, notification.notificationId(), properties.channel(), subscribers);
            if (subscribers != null && subscribers == 0) {
                log.warn("notification_realtime_redis_no_subscribers recipient_id={} notification_id={} channel={}",
                        recipientUserId, notification.notificationId(), properties.channel());
            }
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("notification_realtime_redis_publish_failed recipient_id={} notification_id={} channel={}",
                    recipientUserId,
                    notification == null ? null : notification.notificationId(),
                    properties.channel(),
                    exception);
        }
    }
}
