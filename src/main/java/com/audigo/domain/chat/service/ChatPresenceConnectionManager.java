package com.audigo.domain.chat.service;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatPresenceConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ChatPresenceConnectionManager.class);

    private final RedisChatPresenceStore redisChatPresenceStore;
    private final ChatPresenceRegistry chatPresenceRegistry;
    private final ChatPresenceNotificationService chatPresenceNotificationService;
    private final ChatPresenceProperties properties;
    private final ConcurrentMap<String, ManagedSession> sessionsById = new ConcurrentHashMap<>();

    public ChatPresenceConnectionManager(
            RedisChatPresenceStore redisChatPresenceStore,
            ChatPresenceRegistry chatPresenceRegistry,
            ChatPresenceNotificationService chatPresenceNotificationService,
            ChatPresenceProperties properties
    ) {
        this.redisChatPresenceStore = redisChatPresenceStore;
        this.chatPresenceRegistry = chatPresenceRegistry;
        this.chatPresenceNotificationService = chatPresenceNotificationService;
        this.properties = properties;
    }

    public void connected(Long userId, String sessionId) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId.isBlank()) {
            return;
        }

        ManagedSession session = sessionsById.computeIfAbsent(
                sessionId,
                ignored -> new ManagedSession(userId, sessionId)
        );
        synchronized (session) {
            if (session.disconnected || !Objects.equals(session.userId, userId)) {
                return;
            }
            if (!session.active) {
                session.active = true;
                if (chatPresenceRegistry.connected(userId, sessionId)) {
                    chatPresenceNotificationService.publishStatusChange(userId, true);
                }
            } else if (session.redisRegistered) {
                return;
            }

            register(session);
        }
    }

    public void disconnected(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        ManagedSession session = sessionsById.computeIfAbsent(
                sessionId,
                ignored -> ManagedSession.disconnectedWithoutUser(sessionId, now)
        );
        synchronized (session) {
            if (!session.disconnected) {
                session.active = false;
                session.disconnected = true;
                session.disconnectedAtMillis = now;
                if (session.userId != null && session.redisEntryMayExist) {
                    session.offlineNotificationPending = chatPresenceRegistry.disconnected(
                            session.userId,
                            session.sessionId
                    );
                }
            }
            remove(session);
        }
    }

    @Scheduled(fixedDelayString = "${audigo.chat.presence.renewal-interval:15s}")
    public void renewActiveSessions() {
        long now = System.currentTimeMillis();
        long tombstoneRetentionMillis = properties.keyTtl().toMillis();

        sessionsById.forEach((sessionId, session) -> {
            synchronized (session) {
                if (session.disconnected) {
                    remove(session);
                    publishOfflineIfNeeded(session);
                    if (now - session.disconnectedAtMillis >= tombstoneRetentionMillis) {
                        sessionsById.remove(sessionId, session);
                    }
                    return;
                }
                if (!session.active) {
                    return;
                }

                try {
                    if (!redisChatPresenceStore.renew(session.userId, session.sessionId)) {
                        redisChatPresenceStore.register(session.userId, session.sessionId);
                    }
                    session.redisRegistered = true;
                } catch (RuntimeException exception) {
                    session.redisRegistered = false;
                    log.warn(
                            "chat_presence_renewal_failed user_id={} session_id={}",
                            session.userId,
                            session.sessionId,
                            exception
                    );
                }
            }
        });
    }

    private void register(ManagedSession session) {
        session.redisEntryMayExist = true;
        try {
            redisChatPresenceStore.register(session.userId, session.sessionId);
            session.redisRegistered = true;
        } catch (RuntimeException exception) {
            session.redisRegistered = false;
            log.warn(
                    "chat_presence_registration_failed user_id={} session_id={}",
                    session.userId,
                    session.sessionId,
                    exception
            );
        }
    }

    private void remove(ManagedSession session) {
        if (session.userId == null || !session.redisEntryMayExist) {
            return;
        }
        try {
            redisChatPresenceStore.remove(session.userId, session.sessionId);
            session.redisRegistered = false;
            session.redisEntryMayExist = false;
            session.removalPending = false;
            publishOfflineIfNeeded(session);
        } catch (RuntimeException exception) {
            session.removalPending = true;
            log.warn(
                    "chat_presence_removal_failed user_id={} session_id={}",
                    session.userId,
                    session.sessionId,
                    exception
            );
        }
    }

    private void publishOfflineIfNeeded(ManagedSession session) {
        if (!session.offlineNotificationPending || session.userId == null || session.removalPending) {
            return;
        }
        try {
            if (!redisChatPresenceStore.isOnline(session.userId)) {
                chatPresenceNotificationService.publishStatusChange(session.userId, false);
            }
            session.offlineNotificationPending = false;
        } catch (RuntimeException exception) {
            log.warn(
                    "chat_presence_offline_status_check_failed user_id={} session_id={}",
                    session.userId,
                    session.sessionId,
                    exception
            );
        }
    }

    private static final class ManagedSession {

        private final Long userId;
        private final String sessionId;
        private boolean active;
        private boolean disconnected;
        private boolean redisRegistered;
        private boolean redisEntryMayExist;
        private boolean removalPending;
        private boolean offlineNotificationPending;
        private long disconnectedAtMillis;

        private ManagedSession(Long userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }

        private static ManagedSession disconnectedWithoutUser(String sessionId, long disconnectedAtMillis) {
            ManagedSession session = new ManagedSession(null, sessionId);
            session.disconnected = true;
            session.disconnectedAtMillis = disconnectedAtMillis;
            return session;
        }
    }
}
