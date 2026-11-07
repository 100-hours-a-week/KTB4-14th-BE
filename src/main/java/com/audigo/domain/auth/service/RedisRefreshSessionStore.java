package com.audigo.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisRefreshSessionStore {

    private static final String KEY_PREFIX = "auth:refresh_token:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRefreshSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    boolean create(RefreshSessionToken token, Long userId, String deviceId, Duration ttl) {
        RefreshSessionData session = new RefreshSessionData(userId, deviceId, token.secretHash());
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                key(token.sessionId()), serialize(session), ttl
        ));
    }

    Optional<RefreshSessionData> find(String sessionId) {
        String value = redisTemplate.opsForValue().get(key(sessionId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, RefreshSessionData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid refresh session data.", exception);
        }
    }

    private String serialize(RefreshSessionData session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize refresh session.", exception);
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
