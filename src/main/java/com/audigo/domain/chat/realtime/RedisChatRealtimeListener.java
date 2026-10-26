package com.audigo.domain.chat.realtime;

import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.chat.dto.ChatReadReceiptRealtimeEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class RedisChatRealtimeListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisChatRealtimeListener.class);

    private final ObjectMapper objectMapper;
    private final ChatLocalRealtimeDispatcher localDispatcher;

    public RedisChatRealtimeListener(ObjectMapper objectMapper, ChatLocalRealtimeDispatcher localDispatcher) {
        this.objectMapper = objectMapper;
        this.localDispatcher = localDispatcher;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        ChatRealtimeRedisEvent event = null;
        try {
            event = objectMapper.readValue(message.getBody(), ChatRealtimeRedisEvent.class);
            if (event.recipientUserId() == null || event.recipientUserId() <= 0
                    || event.eventType() == null || event.payload() == null || !event.payload().isObject()) {
                throw new IllegalArgumentException("Invalid chat realtime event envelope");
            }
            localDispatcher.deliver(event.recipientUserId(), readPayload(event));
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("chat_realtime_redis_event_invalid", exception);
        } catch (RuntimeException exception) {
            log.warn(
                    "chat_realtime_local_delivery_failed event_type={} recipient_id={}",
                    event == null ? null : event.eventType(),
                    event == null ? null : event.recipientUserId(),
                    exception
            );
        }
    }

    private Object readPayload(ChatRealtimeRedisEvent event) throws JsonProcessingException {
        JsonNode payload = event.payload();
        return switch (event.eventType()) {
            case MESSAGE_CREATED -> {
                ChatMessageRealtimeEvent value = objectMapper.treeToValue(payload, ChatMessageRealtimeEvent.class);
                if (!"MESSAGE_CREATED".equals(value.eventType()) || value.data() == null
                        || !positive(value.data().messageId()) || !positive(value.data().roomId())
                        || !positive(value.data().senderId()) || value.data().messageSequence() <= 0
                        || value.data().clientMessageId() == null || value.data().clientMessageId().isBlank()
                        || value.data().content() == null || value.data().sentAt() == null) {
                    throw new IllegalArgumentException("Invalid MESSAGE_CREATED payload");
                }
                yield value;
            }
            case READ_RECEIPT_UPDATED -> {
                ChatReadReceiptRealtimeEvent value = objectMapper.treeToValue(
                        payload,
                        ChatReadReceiptRealtimeEvent.class
                );
                if (!"READ_RECEIPT_UPDATED".equals(value.eventType()) || !positive(value.roomId())
                        || !positive(value.readerId()) || value.readThroughMessageSequence() <= 0) {
                    throw new IllegalArgumentException("Invalid READ_RECEIPT_UPDATED payload");
                }
                yield value;
            }
            case PRESENCE_UPDATED -> {
                ChatPresenceRealtimeEvent value = objectMapper.treeToValue(payload, ChatPresenceRealtimeEvent.class);
                if (!"PRESENCE_UPDATED".equals(value.eventType()) || !positive(value.userId())) {
                    throw new IllegalArgumentException("Invalid PRESENCE_UPDATED payload");
                }
                yield value;
            }
        };
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
