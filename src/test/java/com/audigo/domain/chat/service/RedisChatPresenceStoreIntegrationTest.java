package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisChatPresenceStoreIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Duration RENEWAL_INTERVAL = Duration.ofMillis(100);
    private static final Duration LEASE_DURATION = Duration.ofMillis(500);
    private static final Duration KEY_TTL = Duration.ofMillis(1_200);
    private static final AtomicLong NEXT_USER_ID = new AtomicLong(8_000_000_000_000_000L);

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine")
    ).withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisChatPresenceStore presenceStore;

    @BeforeAll
    static void setUpRedisClient() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        presenceStore = new RedisChatPresenceStore(
                redisTemplate,
                new ChatPresenceProperties(RENEWAL_INTERVAL, LEASE_DURATION, KEY_TTL),
                new ChatPresenceInstanceId("integration-test-instance")
        );
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @AfterEach
    void removeTestKeys() {
        for (Long userId : testUserIds) {
            redisTemplate.delete(key(userId));
        }
        testUserIds.clear();
    }

    private final java.util.List<Long> testUserIds = new java.util.ArrayList<>();

    @Test
    void oneRemainingConnectionKeepsUserOnlineUntilTheLastConnectionIsRemoved() {
        Long userId = nextTestUserId();
        presenceStore.register(userId, "session-a");
        presenceStore.register(userId, "session-b");

        assertThat(redisTemplate.opsForZSet().zCard(key(userId))).isEqualTo(2L);
        assertThat(presenceStore.isOnline(userId)).isTrue();

        presenceStore.remove(userId, "session-a");

        assertThat(redisTemplate.opsForZSet().zCard(key(userId))).isEqualTo(1L);
        assertThat(presenceStore.isOnline(userId)).isTrue();

        presenceStore.remove(userId, "session-b");

        assertThat(presenceStore.isOnline(userId)).isFalse();
        assertThat(redisTemplate.hasKey(key(userId))).isFalse();
    }

    @Test
    void deletingTheSameConnectionMoreThanOnceIsSafe() {
        Long userId = nextTestUserId();
        presenceStore.register(userId, "session-a");

        presenceStore.remove(userId, "session-a");
        presenceStore.remove(userId, "session-a");

        assertThat(presenceStore.isOnline(userId)).isFalse();
        assertThat(redisTemplate.hasKey(key(userId))).isFalse();
    }

    @Test
    void lookupRemovesExpiredConnectionsAndDeletesTheEmptyUserKey() throws InterruptedException {
        Long userId = nextTestUserId();
        presenceStore.register(userId, "expired-session");

        Thread.sleep(LEASE_DURATION.toMillis() + 150);

        assertThat(presenceStore.isOnline(userId)).isFalse();
        assertThat(redisTemplate.hasKey(key(userId))).isFalse();
    }

    @Test
    void keyExpiresWhenNoOneQueriesItAfterTheConnectionExpires() throws InterruptedException {
        Long userId = nextTestUserId();
        presenceStore.register(userId, "unobserved-session");

        Thread.sleep(KEY_TTL.toMillis() + 300);

        assertThat(redisTemplate.hasKey(key(userId))).isFalse();
    }

    @Test
    void batchLookupReturnsOnlineAndOfflineUsersAndDeduplicatesIds() {
        Long onlineUserId = nextTestUserId();
        Long offlineUserId = nextTestUserId();
        presenceStore.register(onlineUserId, "active-session");

        Map<Long, Boolean> statuses = presenceStore.findOnlineStatuses(
                List.of(onlineUserId, offlineUserId, onlineUserId)
        );

        assertThat(statuses).containsExactly(
                Map.entry(onlineUserId, true),
                Map.entry(offlineUserId, false)
        );
    }

    @Test
    void redisConnectionFailureIsReportedAsServiceUnavailableInsteadOfOffline() throws IOException {
        int unavailablePort = findUnusedLocalPort();
        LettuceConnectionFactory unavailableConnectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", unavailablePort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build()
        );
        unavailableConnectionFactory.afterPropertiesSet();

        try {
            StringRedisTemplate unavailableTemplate = new StringRedisTemplate(unavailableConnectionFactory);
            unavailableTemplate.afterPropertiesSet();
            RedisChatPresenceStore unavailableStore = new RedisChatPresenceStore(
                    unavailableTemplate,
                    new ChatPresenceProperties(RENEWAL_INTERVAL, LEASE_DURATION, KEY_TTL),
                    new ChatPresenceInstanceId("integration-test-unavailable-instance")
            );
            ChatPresenceQueryService queryService = new ChatPresenceQueryService(unavailableStore);

            assertThatThrownBy(() -> queryService.isOnline(nextTestUserId()))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE)
                    );
        } finally {
            unavailableConnectionFactory.destroy();
        }
    }

    private Long nextTestUserId() {
        Long userId = NEXT_USER_ID.incrementAndGet();
        testUserIds.add(userId);
        return userId;
    }

    private static String key(Long userId) {
        return "chat:presence:user:" + userId;
    }

    private static int findUnusedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
