package com.audigo.domain.notification.service;

import com.audigo.domain.notification.config.NotificationSseProperties;
import com.audigo.domain.notification.dto.NotificationResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationSseService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseService.class);
    private final NotificationSseProperties properties;
    private final Map<Long, List<Connection>> connectionsByUserId = new ConcurrentHashMap<>();
    private final AtomicBoolean redisResyncPending = new AtomicBoolean();

    public NotificationSseService(NotificationSseProperties properties) {
        this.properties = properties;
    }

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = createEmitter();
        Connection connection = new Connection(userId, emitter);
        emitter.onCompletion(() -> close(connection, false));
        emitter.onTimeout(() -> close(connection, true));
        emitter.onError(ignored -> close(connection, false));

        // A concurrent Redis notification must follow the connected/resync events on this stream.
        synchronized (connection) {
            connectionsByUserId.compute(userId, (ignored, connections) -> {
                List<Connection> updated = connections == null ? new CopyOnWriteArrayList<>() : connections;
                updated.add(connection);
                return updated;
            });
            if (send(connection, SseEmitter.event().name("connected").data(Map.of("connected", true)), "connected")) {
                sendResync(connection, "sse_connected");
                log.info("notification_sse_subscribed user_id={}", userId);
            }
        }
        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(properties.timeout().toMillis());
    }

    public void sendNotification(Long userId, NotificationResponse notification) {
        List<Connection> connections = connectionsByUserId.get(userId);
        if (connections == null) {
            return;
        }
        for (Connection connection : connections) {
            send(connection, SseEmitter.event().name("notification").data(notification), "notification");
        }
    }

    public void requestRedisResync() {
        // Subscription callbacks can run on Redis I/O threads. Defer writes and coalesce repeated signals.
        redisResyncPending.set(true);
    }

    @Scheduled(scheduler = "notificationSseScheduler",
            fixedDelayString = "${audigo.notification.sse.heartbeat-interval:15s}")
    public void maintainConnections() {
        boolean resync = redisResyncPending.getAndSet(false);
        connectionsByUserId.forEach((userId, connections) -> {
            for (Connection connection : connections) {
                if (resync) {
                    sendResync(connection, "redis_subscription_ready");
                }
                send(connection, SseEmitter.event().comment("heartbeat"), "heartbeat");
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        connectionsByUserId.forEach((userId, connections) -> connections.forEach(connection -> close(connection, true)));
    }

    private void sendResync(Connection connection, String reason) {
        if (send(connection, SseEmitter.event().name("resync_required").data(Map.of("reason", reason)), "resync_required")) {
            log.debug("notification_sse_resync_sent user_id={} reason={}", connection.userId, reason);
        }
    }

    private boolean send(Connection connection, SseEmitter.SseEventBuilder event, String eventName) {
        synchronized (connection) {
            if (connection.closed) {
                return false;
            }
            try {
                connection.emitter.send(event);
                return true;
            } catch (IOException | RuntimeException exception) {
                // On IOException the servlet container performs the error dispatch and completion.
                close(connection, !(exception instanceof IOException));
                log.warn("notification_sse_send_failed user_id={} event={} reason={}",
                        connection.userId, eventName, exception.getMessage());
                return false;
            }
        }
    }

    private void close(Connection connection, boolean complete) {
        synchronized (connection) {
            if (connection.closed) {
                return;
            }
            connection.closed = true;
            // Remove only this tab, atomically with respect to concurrent subscriptions for the same user.
            connectionsByUserId.computeIfPresent(connection.userId, (userId, connections) -> {
                connections.remove(connection);
                return connections.isEmpty() ? null : connections;
            });
            if (complete) {
                try {
                    connection.emitter.complete();
                } catch (RuntimeException exception) {
                    log.debug("notification_sse_completion_failed user_id={}", connection.userId, exception);
                }
            }
            log.debug("notification_sse_removed user_id={}", connection.userId);
        }
    }

    private static final class Connection {
        private final Long userId;
        private final SseEmitter emitter;
        private boolean closed;

        private Connection(Long userId, SseEmitter emitter) {
            this.userId = userId;
            this.emitter = emitter;
        }
    }
}
