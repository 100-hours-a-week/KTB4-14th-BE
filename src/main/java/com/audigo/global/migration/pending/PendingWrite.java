package com.audigo.global.migration.pending;

import jakarta.servlet.AsyncContext;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

// 보류된 쓰기 요청 1건. 요청 본문은 복사하지 않고 재디스패치 때 원래 경로가 그대로 읽는다
public final class PendingWrite {

    // 응답 상태를 알 수 없이 끝남 (디스패치 실패, 연결 오류)
    public static final int UNKNOWN_STATUS = -1;

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
    // 재개 실행이 끝났을 때 한 번만 호출할 콜백 (응답 상태 코드)
    private final AtomicReference<IntConsumer> finishCallback = new AtomicReference<>();
    private final AtomicBoolean finished = new AtomicBoolean();

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

    // 재디스패치 전에 등록한다
    void onFinish(IntConsumer callback) {
        finishCallback.set(Objects.requireNonNull(callback, "callback"));
    }

    // 재개된 요청의 응답이 끝났을 때 호출된다. DISPATCHED 항목만, 한 번만 콜백을 실행한다
    void finish(int status) {
        if (state.get() != State.DISPATCHED || !finished.compareAndSet(false, true)) {
            return;
        }
        IntConsumer callback = finishCallback.get();
        if (callback != null) {
            callback.accept(status);
        }
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
