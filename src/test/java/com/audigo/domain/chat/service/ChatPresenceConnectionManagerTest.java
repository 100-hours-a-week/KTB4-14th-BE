package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.config.ChatPresenceProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class ChatPresenceConnectionManagerTest {

    private static final Long USER_ID = 42L;
    private static final String SESSION_ID = "session-1";

    @Mock
    private RedisChatPresenceStore redisChatPresenceStore;

    @Mock
    private ChatPresenceRegistry chatPresenceRegistry;

    @Mock
    private ChatPresenceNotificationService chatPresenceNotificationService;

    private ChatPresenceConnectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ChatPresenceConnectionManager(
                redisChatPresenceStore,
                chatPresenceRegistry,
                chatPresenceNotificationService,
                new ChatPresenceProperties(Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(90))
        );
    }

    @Test
    void registers_connected_session_once_and_publishes_local_online_transition() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(true);

        manager.connected(USER_ID, SESSION_ID);
        manager.connected(USER_ID, SESSION_ID);

        verify(redisChatPresenceStore).register(USER_ID, SESSION_ID);
        verify(chatPresenceRegistry).connected(USER_ID, SESSION_ID);
        verify(chatPresenceNotificationService).publishStatusChange(USER_ID, true);
    }

    @Test
    void disconnect_removes_only_that_session_and_duplicate_event_is_safe() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(false);
        when(chatPresenceRegistry.disconnected(USER_ID, SESSION_ID)).thenReturn(true);
        when(redisChatPresenceStore.isOnline(USER_ID)).thenReturn(false);

        manager.connected(USER_ID, SESSION_ID);
        manager.disconnected(SESSION_ID);
        manager.disconnected(SESSION_ID);

        verify(redisChatPresenceStore).remove(USER_ID, SESSION_ID);
        verify(chatPresenceRegistry).disconnected(USER_ID, SESSION_ID);
        verify(chatPresenceNotificationService).publishStatusChange(USER_ID, false);
    }

    @Test
    void renews_active_connection_and_restores_a_missing_redis_record() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(false);
        when(redisChatPresenceStore.renew(USER_ID, SESSION_ID)).thenReturn(false);

        manager.connected(USER_ID, SESSION_ID);
        manager.renewActiveSessions();

        verify(redisChatPresenceStore).renew(USER_ID, SESSION_ID);
        verify(redisChatPresenceStore, times(2)).register(USER_ID, SESSION_ID);
    }

    @Test
    void retries_registration_after_redis_failure_without_claiming_it_succeeded() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(true);
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .doNothing()
                .when(redisChatPresenceStore).register(USER_ID, SESSION_ID);
        when(redisChatPresenceStore.renew(USER_ID, SESSION_ID)).thenReturn(false);

        manager.connected(USER_ID, SESSION_ID);
        verify(chatPresenceNotificationService).publishStatusChange(USER_ID, true);

        manager.renewActiveSessions();

        verify(redisChatPresenceStore, times(2)).register(USER_ID, SESSION_ID);
        verify(chatPresenceNotificationService).publishStatusChange(USER_ID, true);
    }

    @Test
    void does_not_renew_or_register_a_session_after_disconnect() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(false);
        when(chatPresenceRegistry.disconnected(USER_ID, SESSION_ID)).thenReturn(false);

        manager.connected(USER_ID, SESSION_ID);
        manager.disconnected(SESSION_ID);
        manager.renewActiveSessions();

        verify(redisChatPresenceStore).register(USER_ID, SESSION_ID);
        verify(redisChatPresenceStore, never()).renew(USER_ID, SESSION_ID);
        verify(redisChatPresenceStore).remove(USER_ID, SESSION_ID);
    }

    @Test
    void disconnect_before_connect_prevents_late_registration() {
        manager.disconnected(SESSION_ID);
        manager.connected(USER_ID, SESSION_ID);

        verifyNoInteractions(redisChatPresenceStore, chatPresenceRegistry, chatPresenceNotificationService);
    }

    @Test
    void retries_failed_removal_without_renewing_the_closed_session() {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(false);
        when(chatPresenceRegistry.disconnected(USER_ID, SESSION_ID)).thenReturn(false);
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .doNothing()
                .when(redisChatPresenceStore).remove(USER_ID, SESSION_ID);

        manager.connected(USER_ID, SESSION_ID);
        manager.disconnected(SESSION_ID);
        manager.renewActiveSessions();

        verify(redisChatPresenceStore, times(2)).remove(USER_ID, SESSION_ID);
        verify(redisChatPresenceStore, never()).renew(USER_ID, SESSION_ID);
    }

    @Test
    void disconnect_waits_for_in_progress_renewal_then_removes_the_final_record() throws Exception {
        when(chatPresenceRegistry.connected(USER_ID, SESSION_ID)).thenReturn(false);
        when(chatPresenceRegistry.disconnected(USER_ID, SESSION_ID)).thenReturn(false);
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch allowRenewalToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            renewalStarted.countDown();
            assertThat(allowRenewalToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        }).when(redisChatPresenceStore).renew(USER_ID, SESSION_ID);

        manager.connected(USER_ID, SESSION_ID);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> renewal = executor.submit(manager::renewActiveSessions);
            assertThat(renewalStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch disconnectStarted = new CountDownLatch(1);
            Future<?> disconnect = executor.submit(() -> {
                disconnectStarted.countDown();
                manager.disconnected(SESSION_ID);
            });
            assertThat(disconnectStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> disconnect.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowRenewalToFinish.countDown();
            renewal.get(5, TimeUnit.SECONDS);
            disconnect.get(5, TimeUnit.SECONDS);

            InOrder calls = inOrder(redisChatPresenceStore);
            calls.verify(redisChatPresenceStore).register(USER_ID, SESSION_ID);
            calls.verify(redisChatPresenceStore).renew(USER_ID, SESSION_ID);
            calls.verify(redisChatPresenceStore).register(USER_ID, SESSION_ID);
            calls.verify(redisChatPresenceStore).remove(USER_ID, SESSION_ID);
            manager.renewActiveSessions();
            verify(redisChatPresenceStore, times(2)).register(USER_ID, SESSION_ID);
            verify(redisChatPresenceStore, times(1)).remove(USER_ID, SESSION_ID);
        } finally {
            allowRenewalToFinish.countDown();
            executor.shutdownNow();
        }
    }
}
