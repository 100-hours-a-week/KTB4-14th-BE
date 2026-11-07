package com.audigo.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRefreshSessionStore {

    private static final String KEY_PREFIX = "auth:refresh_token:";
    private static final RedisScript<Long> ROTATE_SCRIPT = script("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local session = cjson.decode(value)
            if session.secretHash ~= ARGV[1] then return 0 end
            session.secretHash = ARGV[2]
            redis.call('SET', KEYS[1], cjson.encode(session), 'EX', ARGV[3])
            return 1
            """);
    private static final RedisScript<Long> REVOKE_SCRIPT = script("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local session = cjson.decode(value)
            if session.secretHash ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRefreshSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    boolean create(RefreshSessionToken token, Long userId, String deviceId, Duration ttl) {
        RefreshSessionData session = new RefreshSessionData(userId, deviceId, token.secretHash());
        return withAvailability(() -> Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                key(token.sessionId()), serialize(session), ttl
        )));
    }

    Optional<RefreshSessionData> find(String sessionId) {
        String value = withAvailability(() -> redisTemplate.opsForValue().get(key(sessionId)));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, RefreshSessionData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid refresh session data.", exception);
        }
    }

    boolean rotate(RefreshSessionToken current, RefreshSessionToken next, Duration ttl) {
        if (!current.sessionId().equals(next.sessionId())) {
            throw new IllegalArgumentException("Session ID must remain unchanged during rotation.");
        }
        Long result = withAvailability(() -> redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(key(current.sessionId())),
                current.secretHash(),
                next.secretHash(),
                Long.toString(ttl.toSeconds())
        ));
        return Long.valueOf(1).equals(result);
    }

    boolean revoke(RefreshSessionToken token) {
        Long result = withAvailability(() -> redisTemplate.execute(
                REVOKE_SCRIPT,
                List.of(key(token.sessionId())),
                token.secretHash()
        ));
        return Long.valueOf(1).equals(result);
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

    private <T> T withAvailability(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessResourceFailureException | QueryTimeoutException exception) {
            throw new AuthSessionUnavailableException(exception);
        }
    }

    private static RedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }
}
