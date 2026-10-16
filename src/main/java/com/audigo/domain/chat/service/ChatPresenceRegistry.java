package com.audigo.domain.chat.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class ChatPresenceRegistry {

    private final ConcurrentMap<Long, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    public void connected(Long userId, String sessionId) {
        sessionsByUser.compute(userId, (ignored, sessionIds) -> {
            Set<String> activeSessions = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            activeSessions.add(sessionId);
            return activeSessions;
        });
    }

    public void disconnected(Long userId, String sessionId) {
        sessionsByUser.computeIfPresent(userId, (ignored, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
    }

    public boolean isOnline(Long userId) {
        Set<String> sessionIds = sessionsByUser.get(userId);
        return sessionIds != null && !sessionIds.isEmpty();
    }
}
