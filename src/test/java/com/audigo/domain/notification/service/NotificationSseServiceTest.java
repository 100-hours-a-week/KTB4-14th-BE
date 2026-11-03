package com.audigo.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.audigo.domain.notification.config.NotificationSseProperties;
import com.audigo.domain.notification.dto.NotificationResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseServiceTest {

    private NotificationSseService service;

    @BeforeEach
    void setUp() {
        service = spy(new NotificationSseService(new NotificationSseProperties(null, null)));
    }

    @Test
    void connection_starts_with_connected_then_resync_and_heartbeat_is_a_comment() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).createEmitter();

        service.subscribe(7L);
        service.maintainConnections();

        List<SseEmitter.SseEventBuilder> events = events(emitter, 3);
        assertThat(text(events.get(0))).contains("event:connected");
        assertThat(data(events.get(0)).toString()).contains("connected=true");
        assertThat(text(events.get(1))).contains("event:resync_required");
        assertThat(data(events.get(1)).toString()).contains("reason=sse_connected");
        assertThat(text(events.get(2))).contains(":heartbeat").doesNotContain("event:notification");
    }

    @Test
    void confirmed_redis_subscriptions_coalesce_until_maintenance_then_reset() throws Exception {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        doReturn(first, second).when(service).createEmitter();
        service.subscribe(7L);
        service.subscribe(8L);
        clearInvocations(first, second);

        service.requestRedisResync();
        service.requestRedisResync();
        verifyNoInteractions(first, second);
        service.maintainConnections();

        for (SseEmitter emitter : new SseEmitter[] {first, second}) {
            List<SseEmitter.SseEventBuilder> sent = events(emitter, 2);
            assertThat(text(sent.get(0))).contains("event:resync_required");
            assertThat(data(sent.get(0)).toString()).contains("redis_subscription_ready");
        }
        clearInvocations(first, second);
        service.maintainConnections();
        assertThat(text(events(first, 1).getFirst())).contains(":heartbeat");
        assertThat(text(events(second, 1).getFirst())).contains(":heartbeat");
    }

    @Test
    void completion_of_one_tab_keeps_other_tabs_and_new_connections_for_the_same_user() throws Exception {
        SseEmitter oldTab = mock(SseEmitter.class);
        SseEmitter newTab = mock(SseEmitter.class);
        SseEmitter otherUser = mock(SseEmitter.class);
        doReturn(oldTab, newTab, otherUser).when(service).createEmitter();
        service.subscribe(7L);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(oldTab).onCompletion(completion.capture());
        service.subscribe(7L);
        service.subscribe(8L);
        completion.getValue().run();
        clearInvocations(oldTab, newTab, otherUser);
        NotificationResponse notification = mock(NotificationResponse.class);

        service.sendNotification(7L, notification);

        verifyNoInteractions(oldTab, otherUser);
        assertThat(data(events(newTab, 1).getFirst())).isSameAs(notification);
    }

    @Test
    void timeout_removes_and_completes_the_connection_once() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).createEmitter();
        service.subscribe(7L);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeout.capture());
        clearInvocations(emitter);

        timeout.getValue().run();
        timeout.getValue().run();
        service.maintainConnections();
        service.sendNotification(7L, mock(NotificationResponse.class));

        verify(emitter, times(1)).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void container_error_removes_the_connection_without_completing_it_again() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).createEmitter();
        service.subscribe(7L);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(error.capture());
        clearInvocations(emitter);

        error.getValue().accept(new IOException("disconnected"));
        service.maintainConnections();

        verifyNoInteractions(emitter);
    }

    @Test
    void failed_heartbeat_removes_only_the_failed_tab() throws Exception {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        doReturn(broken, healthy).when(service).createEmitter();
        service.subscribe(7L);
        service.subscribe(7L);
        clearInvocations(broken, healthy);
        doThrow(new IOException("disconnected")).when(broken).send(any(SseEmitter.SseEventBuilder.class));

        service.maintainConnections();
        service.maintainConnections();

        verify(broken, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(broken, never()).complete();
        verify(healthy, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void initial_send_failure_does_not_leave_a_registered_connection() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).createEmitter();
        doThrow(new IllegalStateException("already closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.subscribe(7L);
        service.maintainConnections();

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void shutdown_completes_all_streams_and_drops_pending_delivery() throws Exception {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        doReturn(first, second).when(service).createEmitter();
        service.subscribe(7L);
        service.subscribe(8L);
        clearInvocations(first, second);

        service.shutdown();
        service.requestRedisResync();
        service.maintainConnections();

        verify(first).complete();
        verify(second).complete();
        verify(first, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private List<SseEmitter.SseEventBuilder> events(SseEmitter emitter, int count) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    private String text(SseEmitter.SseEventBuilder event) {
        StringBuilder text = new StringBuilder();
        event.build().forEach(item -> {
            if (item.getData() instanceof String value) {
                text.append(value);
            }
        });
        return text.toString();
    }

    private Object data(SseEmitter.SseEventBuilder event) {
        List<Object> data = new ArrayList<>();
        event.build().forEach(item -> {
            if (!(item.getData() instanceof String)) {
                data.add(item.getData());
            }
        });
        return data.getFirst();
    }
}
