package com.audigo.domain.notification.service;

import com.audigo.domain.notification.dto.NotificationResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationSseService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseService.class);
    private static final long DEFAULT_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<Long, List<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MILLIS);
        emittersByUserId.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ignored -> remove(userId, emitter));

        send(userId, emitter, "connected", Map.of("connected", true));
        log.info("notification_sse_subscribed user_id={}", userId);
        return emitter;
    }

    public void sendNotification(Long userId, NotificationResponse notification) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(userId, emitter, "notification", notification);
        }
    }

    private void send(Long userId, SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException | IllegalStateException exception) {
            remove(userId, emitter);
            log.warn("notification_sse_send_failed user_id={} event={} reason={}", userId, eventName, exception.getMessage());
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUserId.remove(userId);
        }
    }
}
