package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisChatPresenceStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisChatPresenceStore store;

    @BeforeEach
    void setUp() {
        store = new RedisChatPresenceStore(
                redisTemplate,
                new ChatPresenceProperties(Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(90)),
                new ChatPresenceInstanceId("instance-a")
        );
    }

    @Test
    void registerUsesUserSortedSetAndInstanceScopedSessionMember() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        store.register(7L, "stomp-session");

        Invocation invocation = captureInvocation();
        assertThat(invocation.key()).isEqualTo("chat:presence:user:7");
        assertThat(invocation.arguments()).containsExactly("45000", "90000", "instance-a:stomp-session");
        assertThat(invocation.script())
                .contains("redis.call('TIME')")
                .contains("ZREMRANGEBYSCORE")
                .contains("ZADD")
                .contains("PEXPIRE");
    }

    @Test
    void renewReturnsFalseWhenTheConnectionWasRemovedAndDoesNotReinsertIt() {
        doReturn(0L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(store.renew(7L, "stomp-session")).isFalse();

        Invocation invocation = captureInvocation();
        assertThat(invocation.arguments()).containsExactly("45000", "90000", "instance-a:stomp-session");
        assertThat(invocation.script())
                .contains("ZSCORE")
                .contains("return 0")
                .contains("ZADD", "nowMillis + leaseMillis");
    }

    @Test
    void renewReturnsTrueWhenTheConnectionStillExists() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(store.renew(7L, "stomp-session")).isTrue();
    }

    @Test
    void removeIsIdempotentAndDeletesEmptyUserKey() {
        doReturn(0L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        store.remove(7L, "stomp-session");

        Invocation invocation = captureInvocation();
        assertThat(invocation.key()).isEqualTo("chat:presence:user:7");
        assertThat(invocation.arguments()).containsExactly("90000", "instance-a:stomp-session");
        assertThat(invocation.script())
                .contains("ZREM")
                .contains("ZCARD")
                .contains("DEL")
                .contains("PEXPIRE");
    }

    @Test
    void rejectsInvalidUserAndSessionIdentifiersBeforeCallingRedis() {
        assertThatThrownBy(() -> store.register(0L, "stomp-session"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.renew(7L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.remove(-1L, "stomp-session"))
                .isInstanceOf(IllegalArgumentException.class);

        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    @Test
    void eachApplicationInstanceHasItsOwnMemberPrefix() {
        ChatPresenceInstanceId restartedInstance = new ChatPresenceInstanceId("instance-b");
        RedisChatPresenceStore restartedStore = new RedisChatPresenceStore(
                redisTemplate,
                new ChatPresenceProperties(Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(90)),
                restartedInstance
        );
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        restartedStore.register(7L, "stomp-session");

        assertThat(captureInvocation().arguments()).contains("instance-b:stomp-session");
    }

    @Test
    void isOnlineUsesRedisTimeAndRemovesExpiredMembersAndEmptyKeys() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(store.isOnline(7L)).isTrue();

        Invocation invocation = captureInvocation();
        assertThat(invocation.keys()).containsExactly("chat:presence:user:7");
        assertThat(invocation.arguments()).isEmpty();
        assertThat(invocation.script())
                .contains("redis.call('TIME')")
                .contains("ZREMRANGEBYSCORE")
                .contains("ZCARD")
                .contains("DEL")
                .contains("KEYS[1]")
                .doesNotContain("ipairs(KEYS)");
    }

    @Test
    void batchLookupDeduplicatesUserIdsAndReturnsStatusesInInputOrder() {
        doReturn(1L, 0L, 1L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(store.findOnlineStatuses(List.of(7L, 8L, 9L, 7L)))
                .containsExactly(
                        java.util.Map.entry(7L, true),
                        java.util.Map.entry(8L, false),
                        java.util.Map.entry(9L, true)
                );

        List<Invocation> invocations = captureInvocations(3);
        assertThat(invocations).extracting(Invocation::keys).containsExactly(
                List.of("chat:presence:user:7"),
                List.of("chat:presence:user:8"),
                List.of("chat:presence:user:9")
        );
        assertThat(invocations).allSatisfy(invocation -> assertThat(invocation.script())
                .contains("KEYS[1]")
                .doesNotContain("ipairs(KEYS)"));
    }

    @Test
    void batchLookupWithNoUsersDoesNotCallRedis() {
        assertThat(store.findOnlineStatuses(List.of())).isEmpty();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void batchLookupReportsTheFailedItemAndDoesNotReportOffline() {
        RedisConnectionFailureException cause = new RedisConnectionFailureException("Redis unavailable");
        doReturn(1L).doThrow(cause)
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> store.findOnlineStatuses(List.of(7L, 8L, 9L)))
                .isInstanceOfSatisfying(ChatPresenceRedisLookupException.class, exception -> {
                    assertThat(exception.queryIndex()).isEqualTo(2);
                    assertThat(exception.queryTotal()).isEqualTo(3);
                    assertThat(exception.elapsedMillis()).isGreaterThanOrEqualTo(0);
                    assertThat(exception.getCause()).isSameAs(cause);
                });

        assertThat(captureInvocations(2)).extracting(Invocation::keys).containsExactly(
                List.of("chat:presence:user:7"),
                List.of("chat:presence:user:8")
        );
    }

    @Test
    void batchLookupRejectsMalformedRedisResults() {
        doReturn(2L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> store.findOnlineStatuses(List.of(7L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void batchLookupRejectsInvalidUserIdsBeforeCallingRedis() {
        assertThatThrownBy(() -> store.findOnlineStatuses(List.of(7L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(redisTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Invocation captureInvocation() {
        return captureInvocations(1).getFirst();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Invocation> captureInvocations(int count) {
        ArgumentCaptor<RedisScript<?>> scriptCaptor = ArgumentCaptor.forClass((Class) RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(count))
                .execute(scriptCaptor.capture(), keysCaptor.capture(), argumentsCaptor.capture());
        List<Invocation> invocations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            invocations.add(new Invocation(
                    scriptCaptor.getAllValues().get(index).getScriptAsString(),
                    keysCaptor.getAllValues().get(index),
                    java.util.Arrays.stream(argumentsCaptor.getAllValues().get(index)).map(String::valueOf).toList()
            ));
        }
        return invocations;
    }

    private record Invocation(String script, List<String> keys, List<String> arguments) {
        private String key() {
            return keys.getFirst();
        }
    }
}
