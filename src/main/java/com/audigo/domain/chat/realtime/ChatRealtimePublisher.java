package com.audigo.domain.chat.realtime;

import com.audigo.domain.chat.config.ChatRealtimeProperties;
import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.chat.dto.ChatReadReceiptRealtimeEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatRealtimePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatRealtimeProperties properties;

    public ChatRealtimePublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ChatRealtimeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(Long recipientUserId, ChatMessageRealtimeEvent event) {
        publish(recipientUserId, ChatRealtimeEventType.MESSAGE_CREATED, event);
    }

    public void publish(Long recipientUserId, ChatReadReceiptRealtimeEvent event) {
        publish(recipientUserId, ChatRealtimeEventType.READ_RECEIPT_UPDATED, event);
    }

    public void publish(Long recipientUserId, ChatPresenceRealtimeEvent event) {
        publish(recipientUserId, ChatRealtimeEventType.PRESENCE_UPDATED, event);
    }

    private void publish(Long recipientUserId, ChatRealtimeEventType eventType, Object payload) {
        try {
            ChatRealtimeRedisEvent event = new ChatRealtimeRedisEvent(
                    recipientUserId,
                    eventType,
                    objectMapper.valueToTree(payload)
            );
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(properties.channel(), json);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn(
                    "chat_realtime_redis_publish_failed event_type={} recipient_id={} channel={}",
                    eventType,
                    recipientUserId,
                    properties.channel(),
                    exception
            );
        }
    }
}
