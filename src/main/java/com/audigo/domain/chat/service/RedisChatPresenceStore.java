package com.audigo.domain.chat.service;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisChatPresenceStore {

    private static final String KEY_PREFIX = "chat:presence:user:";

    private static final RedisScript<Long> REGISTER_SCRIPT = script("""
            local redisTime = redis.call('TIME')
            local nowMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
            local expiresAt = nowMillis + tonumber(ARGV[1])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis)
            redis.call('ZADD', KEYS[1], expiresAt, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """);

    private static final RedisScript<Long> RENEW_SCRIPT = script("""
            local redisTime = redis.call('TIME')
            local nowMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
            local leaseMillis = tonumber(ARGV[1])
            local keyTtlMillis = tonumber(ARGV[2])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis)
            if not redis.call('ZSCORE', KEYS[1], ARGV[3]) then
                if redis.call('ZCARD', KEYS[1]) == 0 then
                    redis.call('DEL', KEYS[1])
                else
                    redis.call('PEXPIRE', KEYS[1], keyTtlMillis)
                end
                return 0
            end

            redis.call('ZADD', KEYS[1], nowMillis + leaseMillis, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], keyTtlMillis)
            return 1
            """);

    private static final RedisScript<Long> REMOVE_SCRIPT = script("""
            local redisTime = redis.call('TIME')
            local nowMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis)
            local removed = redis.call('ZREM', KEYS[1], ARGV[2])
            if redis.call('ZCARD', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            else
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return removed
            """);

    private final StringRedisTemplate redisTemplate;
    private final ChatPresenceProperties properties;
    private final ChatPresenceInstanceId instanceId;

    public RedisChatPresenceStore(
            StringRedisTemplate redisTemplate,
            ChatPresenceProperties properties,
            ChatPresenceInstanceId instanceId
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.instanceId = instanceId;
    }

    public void register(Long userId, String stompSessionId) {
        executeRequired(
                REGISTER_SCRIPT,
                key(userId),
                millis(properties.leaseDuration()),
                millis(properties.keyTtl()),
                member(stompSessionId)
        );
    }

    public boolean renew(Long userId, String stompSessionId) {
        return executeRequired(
                RENEW_SCRIPT,
                key(userId),
                millis(properties.leaseDuration()),
                millis(properties.keyTtl()),
                member(stompSessionId)
        ) == 1L;
    }

    public void remove(Long userId, String stompSessionId) {
        executeRequired(
                REMOVE_SCRIPT,
                key(userId),
                millis(properties.keyTtl()),
                member(stompSessionId)
        );
    }

    static String key(Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return KEY_PREFIX + userId;
    }

    private String member(String stompSessionId) {
        if (stompSessionId == null || stompSessionId.isBlank()) {
            throw new IllegalArgumentException("stompSessionId must not be blank");
        }
        return instanceId.value() + ":" + stompSessionId;
    }

    private Long executeRequired(RedisScript<Long> script, String key, String... arguments) {
        Long result = redisTemplate.execute(script, List.of(key), (Object[]) arguments);
        if (result == null) {
            throw new IllegalStateException("Redis chat presence script returned no result");
        }
        return result;
    }

    private static String millis(Duration duration) {
        long value = duration.toMillis();
        if (value <= 0) {
            throw new IllegalStateException("Chat presence durations must be at least one millisecond");
        }
        return Long.toString(value);
    }

    private static RedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
