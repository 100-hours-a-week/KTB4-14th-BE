package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Invocation captureInvocation() {
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass((Class) RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), argumentsCaptor.capture());
        return new Invocation(
                scriptCaptor.getValue().getScriptAsString(),
                keysCaptor.getValue().getFirst(),
                java.util.Arrays.stream(argumentsCaptor.getValue()).map(String::valueOf).toList()
        );
    }

    private record Invocation(String script, String key, List<String> arguments) {
    }
}
