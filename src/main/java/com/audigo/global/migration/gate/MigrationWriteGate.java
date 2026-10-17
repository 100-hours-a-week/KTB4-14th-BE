package com.audigo.global.migration.gate;

import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.ai.DeferredAiJob;
import com.audigo.global.migration.ai.DeferredAiJobQueue;
import com.audigo.global.migration.ai.DeferredAiJobStats;
import com.audigo.global.migration.ai.DeferredAiJobsReleasedEvent;
import com.audigo.global.migration.ai.ReleasedAiJob;
import com.audigo.global.migration.tracking.ActiveWriteTransactions;
import com.audigo.global.migration.tracking.InFlightWorkTracker;
import com.audigo.global.migration.tracking.TransactionWriteObserver;
import com.audigo.global.migration.tracking.WorkTicket;
import com.audigo.global.migration.tracking.WorkType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

// 인스턴스 쓰기 단계와 진행 중 작업을 한 lock으로 관리한다.
// 단계 확인 + 작업 등록, 단계 전이 + 잔여 작업 확인이 같은 임계 구역에서 일어나므로
// drain 이후 입장한 쓰기가 freeze 판정을 빠져나가지 않는다.
// 잔여 작업 = 열린 작업 티켓 + 진행 중인 쓰기 트랜잭션. 둘 다 0이어야 freeze 할 수 있다.
// AI 시작 지연은 단계와 별개인 플래그다. drain 전에 켜서 AI 대기 시간을 쓰기 중단 구간 밖으로 옮기고, OPEN 도달 시 끈다
@Component
public class MigrationWriteGate implements TransactionWriteObserver {

    private static final Logger log = LoggerFactory.getLogger(MigrationWriteGate.class);
    private static final Admission HOLD = new Admission.Hold();
    private static final Admission SKIP = new Admission.Skip();

    private final MigrationProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    // Condition 대기를 쓰고, 가상 스레드에서 pinning이 생기지 않도록 synchronized 대신 사용
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();

    private final InFlightWorkTracker tracker = new InFlightWorkTracker();
    private final ActiveWriteTransactions writeTransactions = new ActiveWriteTransactions();
    // drain-timeout 이상 열린 티켓 중 이미 경고한 것
    private final Set<Long> stuckWarnedTicketIds = new HashSet<>();
    private final DeferredAiJobQueue deferredAiJobs = new DeferredAiJobQueue();

    private WritePhase phase = WritePhase.OPEN;
    // 마지막으로 적용한 명령의 migrationId·epoch. OPEN 복귀 후에도 남겨 오래된 명령을 거른다
    private String migrationId;
    private long epoch;
    private ResumeTarget resumeTarget;
    private Instant drainStartedAt;
    private Instant frozenAt;
    private Instant resumeStartedAt;
    private Instant openedAt;
    private boolean aiStartDeferred;
    private Instant aiStartDeferredAt;
    // Hold 판정을 받고 아직 보류 큐에 들어가지 않은 요청 수. RESUMING → OPEN 판정에서 놓치지 않기 위해 센다
    private int holdsInProgress;

    @Autowired
    public MigrationWriteGate(MigrationProperties properties, ApplicationEventPublisher eventPublisher) {
        this(properties, Clock.systemUTC(), eventPublisher);
    }

    MigrationWriteGate(MigrationProperties properties, Clock clock) {
        this(properties, clock, event -> {
        });
    }

    MigrationWriteGate(MigrationProperties properties, Clock clock, ApplicationEventPublisher eventPublisher) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    // ---- 입장 판정 ----

    public Admission tryAdmit(WorkType type, String description) {
        Objects.requireNonNull(type, "type");
        if (type == WorkType.AI_JOB) {
            throw new IllegalArgumentException("AI_JOB은 admitAiJob으로 입장해야 한다");
        }
        lock.lock();
        try {
            if (type == WorkType.SCHEDULED) {
                // AI 시작 지연 중에는 watchdog이 지연 작업을 실패 처리하지 않도록 주기 작업도 건너뛴다
                return phase == WritePhase.OPEN && !aiStartDeferred
                        ? new Admission.Admitted(openTicket(type, description, null))
                        : SKIP;
            }
            if (phase == WritePhase.OPEN) {
                return new Admission.Admitted(openTicket(type, description, null));
            }
            holdsInProgress++;
            return HOLD;
        } finally {
            lock.unlock();
        }
    }

    // 이미 입장한 작업(parent) 안에서 AI 작업을 연다. 부모가 열려 있는 동안 티켓을 열어 잔여가 0이 되는 순간이 없다.
    // AI 시작 지연 중이면 티켓 없이 지연 큐에 넣는다.
    // 부모 티켓이 없는 호출(요청 밖)은 OPEN에서만 바로 실행하고, 그 외 단계에서는 추적 빈틈이 없도록 지연시킨다
    public AiJobStart admitAiJob(WorkTicket parent, long jobId, String description) {
        lock.lock();
        try {
            if (parent != null && !tracker.isOpen(parent)) {
                throw new IllegalStateException("부모 티켓이 열려 있지 않다: " + parent);
            }
            if (aiStartDeferred || (parent == null && phase != WritePhase.OPEN)) {
                deferredAiJobs.add(jobId, now());
                log.info("migration_ai_job_deferred job_id={} phase={} ai_start_deferred={} deferred_size={}",
                        jobId, phase, aiStartDeferred, deferredAiJobs.size());
                return new AiJobStart.Deferred();
            }
            return new AiJobStart.Run(openTicket(WorkType.AI_JOB, description, parent == null ? null : parent.id()));
        } finally {
            lock.unlock();
        }
    }

    // Hold 판정을 받은 요청이 보류 큐에 들어갔거나(또는 거부됐거나) 끝났음을 알린다. Hold마다 정확히 한 번 호출한다
    public void finishHold() {
        lock.lock();
        try {
            if (holdsInProgress > 0) {
                holdsInProgress--;
            }
        } finally {
            lock.unlock();
        }
    }

    // 보류됐다가 재개되는 요청 전용. RESUMING에서만 큐를 거치지 않고 입장한다
    public Admission.Admitted admitReleased(String description) {
        lock.lock();
        try {
            if (phase != WritePhase.RESUMING) {
                throw new IllegalStateException("재개 요청은 RESUMING에서만 입장할 수 있다. 현재 단계: " + phase);
            }
            return new Admission.Admitted(openTicket(WorkType.HTTP_WRITE, description, null));
        } finally {
            lock.unlock();
        }
    }

    // ---- 단계 전이 ----

    // 신규 AI 실행을 컷오버 뒤로 미룬다. 컷오버 시도의 첫 명령이며, 이후 같은 migrationId·epoch로 drain한다.
    // 플래그는 OPEN에 도달할 때(resume 완료) 또는 cancelAiStartDeferral로 꺼진다
    public TransitionResult deferAiStart(PhaseCommand command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (!properties.enabled()) {
                return reject("defer-ai-start", command, TransitionFailure.MIGRATION_DISABLED);
            }
            if (phase == WritePhase.OPEN && !aiStartDeferred) {
                if (isSameOrOlderAttempt(command)) {
                    return reject("defer-ai-start", command, TransitionFailure.STALE_COMMAND);
                }
                migrationId = command.migrationId();
                epoch = command.epoch();
                resumeTarget = null;
                drainStartedAt = null;
                frozenAt = null;
                resumeStartedAt = null;
                openedAt = null;
                enableAiStartDeferral();
                return TransitionResult.applied(snapshotLocked());
            }
            TransitionFailure failure = checkActiveAttempt(command);
            if (failure != null) {
                return reject("defer-ai-start", command, failure);
            }
            if (aiStartDeferred) {
                return TransitionResult.idempotent(snapshotLocked());
            }
            enableAiStartDeferral();
            return TransitionResult.applied(snapshotLocked());
        } finally {
            lock.unlock();
        }
    }

    // drain 전에 컷오버를 포기할 때 AI 시작 지연만 해제한다. drain 이후에는 resume으로 OPEN에 도달해야 해제된다
    public TransitionResult cancelAiStartDeferral(PhaseCommand command) {
        Objects.requireNonNull(command, "command");
        List<ReleasedAiJob> released;
        TransitionResult result;
        lock.lock();
        try {
            if (phase != WritePhase.OPEN) {
                return reject("cancel-ai-start-deferral", command, TransitionFailure.INVALID_TRANSITION);
            }
            if (!aiStartDeferred) {
                return isSameAttempt(command)
                        ? TransitionResult.idempotent(snapshotLocked())
                        : reject("cancel-ai-start-deferral", command, isSameOrOlderAttempt(command)
                                ? TransitionFailure.STALE_COMMAND
                                : TransitionFailure.INVALID_TRANSITION);
            }
            TransitionFailure failure = checkActiveAttempt(command);
            if (failure != null) {
                return reject("cancel-ai-start-deferral", command, failure);
            }
            released = releaseDeferredAiJobsLocked();
            result = TransitionResult.applied(snapshotLocked());
        } finally {
            lock.unlock();
        }
        publishReleased(released);
        return result;
    }

    public TransitionResult drain(PhaseCommand command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (!properties.enabled()) {
                return reject("drain", command, TransitionFailure.MIGRATION_DISABLED);
            }
            if (phase == WritePhase.OPEN) {
                // AI 시작 지연을 켠 시도는 같은 migrationId·epoch로 이어서 drain한다
                TransitionFailure failure = aiStartDeferred
                        ? checkActiveAttempt(command)
                        : isSameOrOlderAttempt(command) ? TransitionFailure.STALE_COMMAND : null;
                if (failure != null) {
                    return reject("drain", command, failure);
                }
                migrationId = command.migrationId();
                epoch = command.epoch();
                resumeTarget = null;
                drainStartedAt = now();
                frozenAt = null;
                resumeStartedAt = null;
                openedAt = null;
                changePhase(WritePhase.DRAINING);
                return TransitionResult.applied(snapshotLocked());
            }
            TransitionFailure failure = checkActiveAttempt(command);
            if (failure != null) {
                return reject("drain", command, failure);
            }
            if (phase == WritePhase.RESUMING) {
                return reject("drain", command, TransitionFailure.INVALID_TRANSITION);
            }
            return TransitionResult.idempotent(snapshotLocked());
        } finally {
            lock.unlock();
        }
    }

    // 진행 중 작업이 0일 때만 FROZEN으로 전이한다
    public TransitionResult freeze(PhaseCommand command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (phase == WritePhase.OPEN) {
                return reject("freeze", command, isSameOrOlderAttempt(command)
                        ? TransitionFailure.STALE_COMMAND
                        : TransitionFailure.INVALID_TRANSITION);
            }
            TransitionFailure failure = checkActiveAttempt(command);
            if (failure != null) {
                return reject("freeze", command, failure);
            }
            return switch (phase) {
                case DRAINING -> {
                    if (!isDrainedLocked()) {
                        List<WorkTicket> remaining = tracker.openTickets();
                        log.warn("migration_command_rejected command=freeze reason={} migration_id={} epoch={} remaining_tickets={} remaining_transactions={}",
                                TransitionFailure.REMAINING_WORK, command.migrationId(), command.epoch(),
                                remaining.size(), writeTransactions.count());
                        warnStuckTickets(remaining);
                        yield TransitionResult.remainingWork(snapshotLocked(), remaining, writeTransactions.list());
                    }
                    frozenAt = now();
                    changePhase(WritePhase.FROZEN);
                    yield TransitionResult.applied(snapshotLocked());
                }
                case FROZEN -> {
                    yield TransitionResult.idempotent(snapshotLocked());
                }
                default -> reject("freeze", command, TransitionFailure.INVALID_TRANSITION);
            };
        } finally {
            lock.unlock();
        }
    }

    // FROZEN에서는 RDS·ORIGIN 모두 가능, DRAINING에서는 ORIGIN(전환 취소)만 가능하다
    public TransitionResult resume(PhaseCommand command, ResumeTarget target) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(target, "target");
        lock.lock();
        try {
            if (phase == WritePhase.OPEN) {
                if (isSameAttempt(command) && resumeTarget == target) {
                    return TransitionResult.idempotent(snapshotLocked());
                }
                return reject("resume", command, isSameOrOlderAttempt(command)
                        ? TransitionFailure.STALE_COMMAND
                        : TransitionFailure.INVALID_TRANSITION);
            }
            TransitionFailure failure = checkActiveAttempt(command);
            if (failure != null) {
                return reject("resume", command, failure);
            }
            return switch (phase) {
                case FROZEN -> startResume(command, target);
                case DRAINING -> target == ResumeTarget.ORIGIN
                        ? startResume(command, target)
                        : reject("resume", command, TransitionFailure.INVALID_TRANSITION);
                case RESUMING -> {
                    if (resumeTarget != target) {
                        yield reject("resume", command, TransitionFailure.INVALID_TRANSITION);
                    }
                    yield TransitionResult.idempotent(snapshotLocked());
                }
                default -> reject("resume", command, TransitionFailure.INVALID_TRANSITION);
            };
        } finally {
            lock.unlock();
        }
    }

    // 보류 큐가 없는 호출용. 운영 경로는 PendingWriteDispatcher가 큐 확인과 함께 호출한다
    public boolean tryCompleteResume() {
        return tryCompleteResume(() -> true);
    }

    // RESUMING → OPEN. 입장 판정과 같은 lock 안에서 '큐에 들어가는 중인 요청 0 + 보류 큐 비었음'을 다시 확인하므로
    // 큐가 비었다고 본 순간 새로 보류된 요청이 OPEN 전이 뒤에 남지 않는다.
    // OPEN 도달과 같은 임계 구역에서 AI 시작 지연을 끄고 지연 작업의 티켓을 연 뒤, lock 밖에서 제출 이벤트를 발행한다
    public boolean tryCompleteResume(BooleanSupplier pendingQueueEmpty) {
        Objects.requireNonNull(pendingQueueEmpty, "pendingQueueEmpty");
        List<ReleasedAiJob> released;
        lock.lock();
        try {
            if (phase != WritePhase.RESUMING || holdsInProgress > 0 || !pendingQueueEmpty.getAsBoolean()) {
                return false;
            }
            openedAt = now();
            changePhase(WritePhase.OPEN);
            released = releaseDeferredAiJobsLocked();
        } finally {
            lock.unlock();
        }
        publishReleased(released);
        return true;
    }

    // 열린 티켓과 쓰기 트랜잭션이 모두 0이 될 때까지 기다린다. 시간 안에 0이 되면 true
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long remainingNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        lock.lock();
        try {
            while (!isDrainedLocked()) {
                if (remainingNanos <= 0) {
                    return false;
                }
                remainingNanos = drained.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ---- 쓰기 트랜잭션 추적 (MigrationAwareJpaTransactionManager가 호출) ----

    // FROZEN 중 쓰기 트랜잭션 거부는 07에서 추가한다
    @Override
    public void beforeWriteBegin(Object transaction, TransactionDefinition definition) {
        lock.lock();
        try {
            writeTransactions.begin(transaction, definition.getName(), now());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void afterCompletion(Object transaction) {
        lock.lock();
        try {
            if (writeTransactions.complete(transaction, now())) {
                signalIfDrained();
            }
        } finally {
            lock.unlock();
        }
    }

    public GateSnapshot snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    public WritePhase phase() {
        lock.lock();
        try {
            return phase;
        } finally {
            lock.unlock();
        }
    }

    public DeferredAiJobStats deferredAiJobStats() {
        lock.lock();
        try {
            return deferredAiJobStatsLocked();
        } finally {
            lock.unlock();
        }
    }

    // ---- 내부 ----

    private void enableAiStartDeferral() {
        aiStartDeferred = true;
        aiStartDeferredAt = now();
        log.info("migration_ai_start_deferred migration_id={} epoch={} phase={}", migrationId, epoch, phase);
    }

    // 지연을 끄고, 지연된 작업마다 AI_JOB 티켓을 연다. 티켓이 먼저 열리므로 다음 drain이 이 작업들을 기다린다
    private List<ReleasedAiJob> releaseDeferredAiJobsLocked() {
        boolean wasDeferred = aiStartDeferred;
        aiStartDeferred = false;
        aiStartDeferredAt = null;
        List<ReleasedAiJob> released = new ArrayList<>();
        for (DeferredAiJob job : deferredAiJobs.drainAll()) {
            WorkTicket ticket = openTicket(WorkType.AI_JOB, "ai-job:" + job.jobId(), null);
            released.add(new ReleasedAiJob(job.jobId(), job.deferredAt(), ticket));
        }
        if (wasDeferred || !released.isEmpty()) {
            log.info("migration_ai_start_released migration_id={} epoch={} released={}",
                    migrationId, epoch, released.size());
        }
        return released;
    }

    // 리스너가 워커에 제출하거나 실패 시 티켓을 닫는다
    private void publishReleased(List<ReleasedAiJob> released) {
        if (!released.isEmpty()) {
            eventPublisher.publishEvent(new DeferredAiJobsReleasedEvent(released));
        }
    }

    private DeferredAiJobStats deferredAiJobStatsLocked() {
        return new DeferredAiJobStats(
                aiStartDeferred,
                aiStartDeferredAt,
                deferredAiJobs.size(),
                deferredAiJobs.oldestWait(now())
        );
    }

    private WorkTicket openTicket(WorkType type, String description, Long parentId) {
        return tracker.open(type, description, parentId, now(), this::release);
    }

    private void release(WorkTicket ticket) {
        lock.lock();
        try {
            if (tracker.release(ticket)) {
                stuckWarnedTicketIds.remove(ticket.id());
                signalIfDrained();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isDrainedLocked() {
        return tracker.total() == 0 && writeTransactions.count() == 0;
    }

    private void signalIfDrained() {
        if (isDrainedLocked()) {
            drained.signalAll();
        }
    }

    private void warnStuckTickets(List<WorkTicket> openTickets) {
        Instant now = now();
        Duration limit = properties.drainTimeout();
        for (WorkTicket ticket : openTickets) {
            Duration age = Duration.between(ticket.openedAt(), now);
            if (age.compareTo(limit) >= 0 && stuckWarnedTicketIds.add(ticket.id())) {
                log.warn("migration_ticket_stuck ticket_id={} type={} description={} thread={} age_ms={}",
                        ticket.id(), ticket.type(), ticket.description(), ticket.threadName(), age.toMillis());
            }
        }
    }

    private TransitionResult startResume(PhaseCommand command, ResumeTarget target) {
        resumeTarget = target;
        resumeStartedAt = now();
        changePhase(WritePhase.RESUMING);
        return TransitionResult.applied(snapshotLocked());
    }

    // 진행 중인 시도와 같은 migrationId·epoch의 명령만 받는다.
    // 더 큰 epoch는 이어받지 않는다. 기존 시도를 같은 epoch로 끝내고 OPEN이 된 뒤 새 epoch로 시작해야 한다
    private TransitionFailure checkActiveAttempt(PhaseCommand command) {
        if (!command.migrationId().equals(migrationId)) {
            return TransitionFailure.MIGRATION_ID_MISMATCH;
        }
        if (command.epoch() < epoch) {
            return TransitionFailure.STALE_COMMAND;
        }
        if (command.epoch() > epoch) {
            return TransitionFailure.ATTEMPT_IN_PROGRESS;
        }
        return null;
    }

    private boolean isSameAttempt(PhaseCommand command) {
        return command.migrationId().equals(migrationId) && command.epoch() == epoch;
    }

    private boolean isSameOrOlderAttempt(PhaseCommand command) {
        return command.migrationId().equals(migrationId) && command.epoch() <= epoch;
    }

    private void changePhase(WritePhase next) {
        WritePhase previous = phase;
        phase = next;
        log.info("migration_phase_changed from={} to={} migration_id={} epoch={} resume_target={}",
                previous, next, migrationId, epoch, resumeTarget);
    }

    private TransitionResult reject(String commandName, PhaseCommand command, TransitionFailure failure) {
        log.warn("migration_command_rejected command={} reason={} migration_id={} epoch={} phase={} current_migration_id={} current_epoch={}",
                commandName, failure, command.migrationId(), command.epoch(), phase, migrationId, epoch);
        return TransitionResult.rejected(failure, snapshotLocked());
    }

    private GateSnapshot snapshotLocked() {
        return new GateSnapshot(
                phase,
                migrationId,
                epoch,
                resumeTarget,
                tracker.total(),
                tracker.countByType(),
                writeTransactions.count(),
                writeTransactions.lastCompletedAt(),
                drainStartedAt,
                frozenAt,
                resumeStartedAt,
                openedAt,
                deferredAiJobStatsLocked()
        );
    }

    private Instant now() {
        return clock.instant();
    }
}
