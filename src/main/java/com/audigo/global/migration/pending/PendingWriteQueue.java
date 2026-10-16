package com.audigo.global.migration.pending;

import com.audigo.global.error.ErrorCode;
import com.audigo.global.logging.RequestLoggingFilter;
import com.audigo.global.migration.MigrationProperties;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

// 보류된 쓰기 요청을 도착 순서대로 보관한다. 요청 스레드는 Servlet 비동기로 즉시 반납한다
@Component
public class PendingWriteQueue {

    private static final Logger log = LoggerFactory.getLogger(PendingWriteQueue.class);
    private static final int SUMMARY_LIMIT = 20;

    private final MigrationProperties properties;
    private final MigrationErrorResponseWriter errorResponseWriter;
    private final Clock clock;
    // STATELESS 보안 설정의 기본 저장소와 같은 속성 이름을 쓴다. ASYNC 재디스패치 때 SecurityContextHolderFilter가 복원한다
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    private final ConcurrentLinkedDeque<PendingWrite> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    @Autowired
    public PendingWriteQueue(MigrationProperties properties, MigrationErrorResponseWriter errorResponseWriter) {
        this(properties, errorResponseWriter, Clock.systemUTC());
    }

    PendingWriteQueue(MigrationProperties properties, MigrationErrorResponseWriter errorResponseWriter, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // 요청을 보류한다. 용량을 넘으면 즉시 503으로 응답하고 null을 반환한다
    public PendingWrite hold(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (size.incrementAndGet() > properties.pendingCapacity()) {
            size.decrementAndGet();
            rejected.incrementAndGet();
            log.warn("migration_write_rejected reason=queue_full method={} uri={} request_id={} capacity={}",
                    request.getMethod(), request.getRequestURI(), requestId(request), properties.pendingCapacity());
            errorResponseWriter.writeRetryLater(response, ErrorCode.MIGRATION_PENDING_QUEUE_FULL);
            return null;
        }

        PendingWrite pending;
        try {
            AsyncContext asyncContext = request.startAsync(request, response);
            asyncContext.setTimeout(properties.holdTimeout().toMillis());
            saveSecurityContext(request, response);

            pending = new PendingWrite(
                    sequence.incrementAndGet(),
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    request.getMethod(),
                    request.getRequestURI(),
                    requestId(request),
                    currentUserId(),
                    asyncContext
            );
            request.setAttribute(RequestLoggingFilter.HELD_ATTRIBUTE, Boolean.TRUE);
            asyncContext.addListener(new HoldListener(pending));
            queue.addLast(pending);
        } catch (RuntimeException exception) {
            size.decrementAndGet();
            throw exception;
        }

        held.incrementAndGet();
        log.info("migration_write_held id={} method={} uri={} request_id={} user_id={} queue_size={}",
                pending.id(), pending.method(), pending.uri(), pending.requestId(), pending.userId(), size.get());
        return pending;
    }

    // 다음 대기 요청을 DISPATCHED로 바꿔 꺼낸다. 시간 초과·이탈 항목은 건너뛴다. 비었으면 null
    public PendingWrite pollForDispatch() {
        PendingWrite next;
        while ((next = queue.pollFirst()) != null) {
            if (next.transition(PendingWrite.State.QUEUED, PendingWrite.State.DISPATCHED)) {
                size.decrementAndGet();
                dispatched.incrementAndGet();
                return next;
            }
        }
        return null;
    }

    public int size() {
        return size.get();
    }

    public boolean isEmpty() {
        return size.get() == 0;
    }

    public PendingQueueStats stats() {
        Instant now = clock.instant();
        List<PendingWriteSummary> head = new ArrayList<>();
        Duration oldestWait = Duration.ZERO;
        for (PendingWrite pending : queue) {
            if (pending.state() != PendingWrite.State.QUEUED) {
                continue;
            }
            Duration waited = Duration.between(pending.enqueuedAt(), now);
            if (head.isEmpty()) {
                oldestWait = waited;
            }
            head.add(new PendingWriteSummary(
                    pending.id(), pending.method(), pending.uri(), pending.requestId(), pending.userId(), waited));
            if (head.size() >= SUMMARY_LIMIT) {
                break;
            }
        }
        return new PendingQueueStats(
                size.get(),
                oldestWait,
                held.get(),
                dispatched.get(),
                timedOut.get(),
                abandoned.get(),
                rejected.get(),
                head
        );
    }

    private void leaveQueue(PendingWrite pending) {
        size.decrementAndGet();
        queue.remove(pending);
    }

    private void saveSecurityContext(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null) {
            securityContextRepository.saveContext(context, request, response);
        }
    }

    private static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? null : requestId.toString();
    }

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    private final class HoldListener implements AsyncListener {

        private final PendingWrite pending;

        private HoldListener(PendingWrite pending) {
            this.pending = pending;
        }

        // QUEUED → TIMED_OUT에 성공했을 때만 응답한다. 이미 DISPATCHED면 실행 쪽이 응답한다
        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            if (!pending.transition(PendingWrite.State.QUEUED, PendingWrite.State.TIMED_OUT)) {
                return;
            }
            leaveQueue(pending);
            timedOut.incrementAndGet();
            log.warn("migration_write_hold_timeout id={} method={} uri={} request_id={} waited_ms={}",
                    pending.id(), pending.method(), pending.uri(), pending.requestId(), waitedMillis());
            try {
                errorResponseWriter.writeRetryLater(
                        (HttpServletResponse) event.getAsyncContext().getResponse(),
                        ErrorCode.MIGRATION_HOLD_TIMEOUT
                );
            } finally {
                event.getAsyncContext().complete();
            }
        }

        // 클라이언트 연결 종료. 응답을 받을 수 없으므로 실행하지 않는다 (재시도와 겹친 중복 쓰기 방지)
        @Override
        public void onError(AsyncEvent event) {
            if (pending.transition(PendingWrite.State.QUEUED, PendingWrite.State.ABANDONED)) {
                leaveQueue(pending);
                abandoned.incrementAndGet();
                log.warn("migration_write_abandoned id={} method={} uri={} request_id={} waited_ms={} reason={}",
                        pending.id(), pending.method(), pending.uri(), pending.requestId(), waitedMillis(),
                        event.getThrowable() == null ? "-" : event.getThrowable().getClass().getSimpleName());
                completeQuietly(event.getAsyncContext());
            }
        }

        // 실행 없이 끝난 경우(컨테이너 종료 등)도 큐에서 뺀다
        @Override
        public void onComplete(AsyncEvent event) {
            if (pending.transition(PendingWrite.State.QUEUED, PendingWrite.State.ABANDONED)) {
                leaveQueue(pending);
                abandoned.incrementAndGet();
                log.warn("migration_write_abandoned id={} method={} uri={} request_id={} waited_ms={} reason=completed",
                        pending.id(), pending.method(), pending.uri(), pending.requestId(), waitedMillis());
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private long waitedMillis() {
            return Duration.between(pending.enqueuedAt(), clock.instant()).toMillis();
        }

        private void completeQuietly(AsyncContext asyncContext) {
            try {
                asyncContext.complete();
            } catch (IllegalStateException ignored) {
                // 컨테이너가 이미 종료한 요청
            }
        }
    }
}
