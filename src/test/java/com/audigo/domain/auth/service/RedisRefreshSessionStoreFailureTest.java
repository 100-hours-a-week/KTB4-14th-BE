package com.audigo.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRefreshSessionStoreFailureTest {

    @Test
    void connectionFailureIsReportedAsAuthUnavailable() {
        assertFailure(new DataAccessResourceFailureException("connection failed"));
    }

    @Test
    void timeoutIsReportedAsAuthUnavailable() {
        assertFailure(new QueryTimeoutException("timed out"));
    }

    private void assertFailure(RuntimeException failure) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:refresh_token:session-a")).thenThrow(failure);
        RedisRefreshSessionStore store = new RedisRefreshSessionStore(redis, new ObjectMapper());

        assertThatThrownBy(() -> store.find("session-a"))
                .isInstanceOf(AuthSessionUnavailableException.class)
                .hasCause(failure);
    }
}
