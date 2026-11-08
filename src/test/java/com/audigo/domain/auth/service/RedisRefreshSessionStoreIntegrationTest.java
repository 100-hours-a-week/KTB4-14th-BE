package com.audigo.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisRefreshSessionStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisRefreshSessionStore store;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        store = new RedisRefreshSessionStore(redisTemplate, mapper);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void creationStoresSessionMetadataAndExpiry() {
        RefreshSessionToken token = RefreshSessionToken.create();
        Duration ttl = Duration.ofMinutes(5);

        assertThat(store.create(token, 42L, ttl)).isTrue();
        assertThat(store.find(token.sessionId())).contains(new RefreshSessionData(42L, token.secretHash()));
        assertThat(redisTemplate.opsForValue().get("auth:refresh_token:" + token.sessionId()))
                .doesNotContain("deviceId");
        assertThat(redisTemplate.getExpire("auth:refresh_token:" + token.sessionId())).isBetween(1L, ttl.toSeconds());
        assertThat(store.create(token, 42L, ttl)).isFalse();
    }

    @Test
    void rotationChangesSecretAndResetsExpiry() {
        RefreshSessionToken original = RefreshSessionToken.create();
        RefreshSessionToken rotated = original.rotate();
        assertThat(store.create(original, 42L, Duration.ofMinutes(1))).isTrue();

        assertThat(store.rotate(original, rotated, Duration.ofDays(14))).isTrue();

        assertThat(store.find(original.sessionId())).contains(
                new RefreshSessionData(42L, rotated.secretHash())
        );
        assertThat(redisTemplate.getExpire("auth:refresh_token:" + original.sessionId()))
                .isGreaterThan(Duration.ofDays(13).toSeconds());
        assertThat(store.rotate(original, original.rotate(), Duration.ofDays(14))).isFalse();
        assertThat(store.revoke(original)).isFalse();
        assertThat(store.revoke(rotated)).isTrue();
        assertThat(store.find(original.sessionId())).isEmpty();
    }

    @Test
    void rotationRemovesDeviceIdFromExistingSession() {
        RefreshSessionToken original = RefreshSessionToken.create();
        String key = "auth:refresh_token:" + original.sessionId();
        redisTemplate.opsForValue().set(key,
                "{\"userId\":42,\"deviceId\":null,\"secretHash\":\"" + original.secretHash() + "\"}",
                Duration.ofDays(14));

        assertThat(store.find(original.sessionId())).contains(new RefreshSessionData(42L, original.secretHash()));
        assertThat(store.rotate(original, original.rotate(), Duration.ofDays(14))).isTrue();
        assertThat(redisTemplate.opsForValue().get(key)).doesNotContain("deviceId");
    }

    @Test
    void concurrentRotationAllowsOnlyOneRequest() throws Exception {
        RefreshSessionToken original = RefreshSessionToken.create();
        assertThat(store.create(original, 42L, Duration.ofDays(14))).isTrue();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return store.rotate(original, original.rotate(), Duration.ofDays(14));
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return store.rotate(original, original.rotate(), Duration.ofDays(14));
            });
            start.countDown();
            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0)).isEqualTo(1);
        }
    }

    @Test
    void expiredSessionCannotBeRotatedOrRevoked() throws InterruptedException {
        RefreshSessionToken token = RefreshSessionToken.create();
        assertThat(store.create(token, 42L, Duration.ofMillis(100))).isTrue();
        Thread.sleep(200);

        assertThat(store.rotate(token, token.rotate(), Duration.ofDays(14))).isFalse();
        assertThat(store.revoke(token)).isFalse();
    }
}
