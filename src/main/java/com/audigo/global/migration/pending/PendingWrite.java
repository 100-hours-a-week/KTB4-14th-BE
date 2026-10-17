package com.audigo.global.migration.pending;

import jakarta.servlet.AsyncContext;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// 보류된 쓰기 요청 1건. 요청 본문은 복사하지 않고 재디스패치 때 원래 경로가 그대로 읽는다
public final class PendingWrite {

    public enum State {
        // 큐에서 대기 중
        QUEUED,
        // 재개를 위해 꺼냄
        DISPATCHED,
        // 보류 시간 초과로 503 응답
        TIMED_OUT,
        // 클라이언트 연결 종료. 실행하지 않음
        ABANDONED
    }

    private final long sequence;
    private final String id;
    private final Instant enqueuedAt;
    private final String method;
    private final String uri;
    private final String requestId;
    private final Long userId;
    private final AsyncContext asyncContext;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    // 재디스패치된 요청이 한 번만 입장하도록 표시
    private final AtomicBoolean released = new AtomicBoolean();

    PendingWrite(
            long sequence,
            String id,
            Instant enqueuedAt,
            String method,
            String uri,
            String requestId,
            Long userId,
            AsyncContext asyncContext
    ) {
        this.sequence = sequence;
        this.id = Objects.requireNonNull(id, "id");
        this.enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        this.method = method;
        this.uri = uri;
        this.requestId = requestId;
        this.userId = userId;
        this.asyncContext = Objects.requireNonNull(asyncContext, "asyncContext");
    }

    boolean transition(State from, State to) {
        return state.compareAndSet(from, to);
    }

    // 재디스패치 입장은 DISPATCHED 상태에서 한 번만 허용한다
    boolean markReleased() {
        return state.get() == State.DISPATCHED && released.compareAndSet(false, true);
    }

    public State state() {
        return state.get();
    }

    public long sequence() {
        return sequence;
    }

    public String id() {
        return id;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public String requestId() {
        return requestId;
    }

    public Long userId() {
        return userId;
    }

    public AsyncContext asyncContext() {
        return asyncContext;
    }

    @Override
    public String toString() {
        return "PendingWrite{id=" + id + ", " + method + " " + uri + ", state=" + state.get() + "}";
    }
}
