package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class ChatPresenceQueryServiceTest {

    @Mock
    private RedisChatPresenceStore redisChatPresenceStore;

    @InjectMocks
    private ChatPresenceQueryService chatPresenceQueryService;

    @Test
    void returns_batched_statuses_from_redis() {
        when(redisChatPresenceStore.findOnlineStatuses(List.of(2L, 3L)))
                .thenReturn(Map.of(2L, true, 3L, false));

        Map<Long, Boolean> statuses = chatPresenceQueryService.findOnlineStatuses(List.of(2L, 3L));

        assertThat(statuses).containsExactlyEntriesOf(Map.of(2L, true, 3L, false));
        verify(redisChatPresenceStore).findOnlineStatuses(List.of(2L, 3L));
    }

    @Test
    void reports_service_unavailable_when_redis_query_fails() {
        when(redisChatPresenceStore.isOnline(2L))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        assertThatThrownBy(() -> chatPresenceQueryService.isOnline(2L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE)
                );
    }

    @Test
    void reports_service_unavailable_when_batched_redis_query_fails() {
        when(redisChatPresenceStore.findOnlineStatuses(List.of(2L, 3L)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        assertThatThrownBy(() -> chatPresenceQueryService.findOnlineStatuses(List.of(2L, 3L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE)
                );
    }
}
