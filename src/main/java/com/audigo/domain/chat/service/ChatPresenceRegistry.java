package com.audigo.domain.chat.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ChatPresenceRegistry {

    private final ConcurrentMap<Long, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    public boolean connected(Long userId, String sessionId) {
        AtomicBoolean becameOnline = new AtomicBoolean();
        sessionsByUser.compute(userId, (ignored, sessionIds) -> {
            boolean wasOnline = sessionIds != null && !sessionIds.isEmpty();
            Set<String> activeSessions = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            activeSessions.add(sessionId);
            becameOnline.set(!wasOnline && !activeSessions.isEmpty());
            return activeSessions;
        });
        return becameOnline.get();
    }

    public boolean disconnected(Long userId, String sessionId) {
        AtomicBoolean becameOffline = new AtomicBoolean();
        sessionsByUser.computeIfPresent(userId, (ignored, sessionIds) -> {
            boolean removed = sessionIds.remove(sessionId);
            if (removed && sessionIds.isEmpty()) {
                becameOffline.set(true);
                return null;
            }
            return sessionIds;
        });
        return becameOffline.get();
    }

    public boolean isOnline(Long userId) {
        Set<String> sessionIds = sessionsByUser.get(userId);
        return sessionIds != null && !sessionIds.isEmpty();
    }
}
