package com.audigo.global.migration.pending;

import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.gate.PhaseCommand;
import com.audigo.global.migration.gate.ResumeTarget;
import com.audigo.global.migration.gate.TransitionResult;
import com.audigo.global.migration.gate.WritePhase;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// resume 이후 보류 큐를 FIFO 순서로, 최대 resume-max-concurrency개씩 원래 처리 경로(ASYNC 재디스패치)로 실행한다.
// 단일 루프 스레드가 permit을 얻은 뒤 큐에서 꺼내고(QUEUED → DISPATCHED CAS), 응답이 끝나면 permit을 반납한다.
// 큐가 비고 재디스패치한 요청이 모두 끝나면 Gate lock 안에서 다시 확인해 OPEN으로 전이한다.
// 실행 중인 재개 요청이 남은 채 OPEN이 되면 그 요청의 admitReleased(RESUMING 전용)가 실패하므로 끝까지 기다린다
@Component
public class PendingWriteDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PendingWriteDispatcher.class);
    private static final long IDLE_WAIT_MILLIS = 10;

    private final MigrationWriteGate gate;
    private final PendingWriteQueue queue;
    private final Clock clock;
    private final int maxConcurrency;
    private final Semaphore permits;
    private final ExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean();

    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong maxWaitMillis = new AtomicLong();
    private volatile long skippedBase;
    private volatile Long skippedAtCompletion;
    private volatile Instant startedAt;
    private volatile Instant completedAt;

    @Autowired
    public PendingWriteDispatcher(MigrationWriteGate gate, PendingWriteQueue queue, MigrationProperties properties) {
        this(gate, queue, properties, Clock.systemUTC());
    }

    PendingWriteDispatcher(
            MigrationWriteGate gate,
            PendingWriteQueue queue,
            MigrationProperties properties,
            Clock clock
    ) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxConcurrency = properties.resumeMaxConcurrency();
        this.permits = new Semaphore(maxConcurrency);
        this.loop = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "migration-resume");
            thread.setDaemon(true);
            return thread;
        });
    }

    // 재개 명령 진입점. Gate가 RESUMING이면 재개 루프를 시작한다.
    // 같은 명령이 다시 와도 Gate가 멱등 처리하고 루프는 하나만 돈다
    public TransitionResult resume(PhaseCommand command, ResumeTarget target) {
        TransitionResult result = gate.resume(command, target);
        if (result.isSuccess() && result.snapshot().phase() == WritePhase.RESUMING) {
            start();
        }
        return result;
    }

    // 루프가 이미 돌고 있으면 false
    boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        resetStats();
        try {
            loop.execute(this::drainQueue);
        } catch (RejectedExecutionException exception) {
            running.set(false);
            throw exception;
        }
        return true;
    }

    public ResumeStats stats() {
        Long skippedFinal = skippedAtCompletion;
        long skipped = (skippedFinal == null ? queue.leftWithoutDispatch() : skippedFinal) - skippedBase;
        return new ResumeStats(
                running.get(),
                startedAt,
                completedAt,
                dispatched.get(),
                succeeded.get(),
                failed.get(),
                skipped,
                Duration.ofMillis(maxWaitMillis.get())
        );
    }

    @PreDestroy
    void shutdown() {
        loop.shutdownNow();
    }

    private void drainQueue() {
        log.info("migration_resume_started queue_size={} max_concurrency={}",
                queue.size(), maxConcurrency);
        try {
            while (true) {
                permits.acquire();
                PendingWrite next = queue.pollForDispatch();
                if (next == null) {
                    permits.release();
                    // 디스패치는 이 루프만 하므로, 모든 permit이 돌아왔으면 실행 중인 재개 요청이 없다
                    if (permits.availablePermits() == maxConcurrency && gate.tryCompleteResume(queue::isEmpty)) {
                        complete();
                        return;
                    }
                    if (gate.phase() != WritePhase.RESUMING) {
                        log.warn("migration_resume_stopped reason=phase_changed phase={}", gate.phase());
                        return;
                    }
                    // 재개 요청이 실행 중이거나, 큐에 들어가는 중인 요청이 있거나, 방금 새 요청이 보류됐다
                    Thread.sleep(IDLE_WAIT_MILLIS);
                    continue;
                }
                dispatch(next);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("migration_resume_stopped reason=interrupted queue_size={}", queue.size());
        } catch (RuntimeException exception) {
            log.error("migration_resume_stopped reason=error queue_size={}", queue.size(), exception);
        } finally {
            running.set(false);
        }
    }

    // permit은 응답 완료(또는 디스패치 실패) 때 finish 콜백에서 반납한다
    private void dispatch(PendingWrite pending) {
        long waited = Duration.between(pending.enqueuedAt(), clock.instant()).toMillis();
        maxWaitMillis.accumulateAndGet(waited, Math::max);
        dispatched.incrementAndGet();
        pending.onFinish(this::onFinished);
        try {
            AsyncContext asyncContext = pending.asyncContext();
            asyncContext.getRequest().setAttribute(MigrationWriteGateInterceptor.RELEASED_ATTR, pending);
            asyncContext.dispatch();
            log.debug("migration_write_dispatched id={} method={} uri={} request_id={} waited_ms={}",
                    pending.id(), pending.method(), pending.uri(), pending.requestId(), waited);
        } catch (RuntimeException exception) {
            log.warn("migration_write_dispatch_failed id={} method={} uri={} request_id={}",
                    pending.id(), pending.method(), pending.uri(), pending.requestId(), exception);
            pending.finish(PendingWrite.UNKNOWN_STATUS);
        }
    }

    private void onFinished(int status) {
        try {
            if (status > 0 && status < 400) {
                succeeded.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        } finally {
            permits.release();
        }
    }

    private void complete() {
        completedAt = clock.instant();
        skippedAtCompletion = queue.leftWithoutDispatch();
        ResumeStats stats = stats();
        log.info("migration_resume_completed dispatched={} succeeded={} failed={} skipped={} max_wait_ms={} duration_ms={}",
                stats.dispatched(), stats.succeeded(), stats.failed(), stats.skipped(),
                stats.maxWait().toMillis(), Duration.between(startedAt, completedAt).toMillis());
    }

    private void resetStats() {
        dispatched.set(0);
        succeeded.set(0);
        failed.set(0);
        maxWaitMillis.set(0);
        skippedBase = queue.leftWithoutDispatch();
        skippedAtCompletion = null;
        startedAt = clock.instant();
        completedAt = null;
    }
}
