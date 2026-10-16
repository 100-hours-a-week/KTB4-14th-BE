package com.audigo.global.migration.gate;

import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.tracking.WorkTicket;
import com.audigo.global.migration.tracking.WorkType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 인스턴스 쓰기 단계와 진행 중 작업을 한 lock으로 관리한다.
// 단계 확인 + 작업 등록, 단계 전이 + 잔여 작업 확인이 같은 임계 구역에서 일어나므로
// drain 이후 입장한 쓰기가 freeze 판정을 빠져나가지 않는다.
@Component
public class MigrationWriteGate {

    private static final Logger log = LoggerFactory.getLogger(MigrationWriteGate.class);
    private static final Admission HOLD = new Admission.Hold();
    private static final Admission SKIP = new Admission.Skip();

    private final MigrationProperties properties;
    private final Clock clock;

    // Condition 대기를 쓰고, 가상 스레드에서 pinning이 생기지 않도록 synchronized 대신 사용
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();

    // 04에서 InFlightWorkTracker로 교체. 삽입 순서 = 오래된 순
    private final Map<Long, WorkTicket> inFlight = new LinkedHashMap<>();
    private long nextTicketId = 1;

    private WritePhase phase = WritePhase.OPEN;
    // 마지막으로 적용한 명령의 migrationId·epoch. OPEN 복귀 후에도 남겨 오래된 명령을 거른다
    private String migrationId;
    private long epoch;
    private ResumeTarget resumeTarget;
    private Instant drainStartedAt;
    private Instant frozenAt;
    private Instant resumeStartedAt;
    private Instant openedAt;

    @Autowired
    public MigrationWriteGate(MigrationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MigrationWriteGate(MigrationProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- 입장 판정 ----

    public Admission tryAdmit(WorkType type, String description) {
        Objects.requireNonNull(type, "type");
        if (type == WorkType.AI_JOB) {
            throw new IllegalArgumentException("AI_JOB은 admitAiJob으로 부모 티켓과 함께 입장해야 한다");
        }
        lock.lock();
        try {
            if (phase == WritePhase.OPEN) {
                return new Admission.Admitted(openTicket(type, description, null));
            }
            return type == WorkType.SCHEDULED ? SKIP : HOLD;
        } finally {
            lock.unlock();
        }
    }

    // 이미 입장한 작업 안에서 AI 작업을 연다. 부모가 열려 있는 동안에는 단계와 상관없이 허용한다
    public Admission.Admitted admitAiJob(WorkTicket parent, String description) {
        Objects.requireNonNull(parent, "parent");
        lock.lock();
        try {
            if (parent.isClosed() || inFlight.get(parent.id()) != parent) {
                throw new IllegalStateException("부모 티켓이 열려 있지 않다: " + parent);
            }
            return new Admission.Admitted(openTicket(WorkType.AI_JOB, description, parent.id()));
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

    public TransitionResult drain(PhaseCommand command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (!properties.enabled()) {
                return reject("drain", command, TransitionFailure.MIGRATION_DISABLED);
            }
            if (phase == WritePhase.OPEN) {
                if (isSameOrOlderAttempt(command)) {
                    return reject("drain", command, TransitionFailure.STALE_COMMAND);
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
                    if (!inFlight.isEmpty()) {
                        List<WorkTicket> remaining = new ArrayList<>(inFlight.values());
                        log.warn("migration_command_rejected command=freeze reason={} migration_id={} epoch={} remaining={}",
                                TransitionFailure.REMAINING_WORK, command.migrationId(), command.epoch(), remaining.size());
                        yield TransitionResult.remainingWork(snapshotLocked(), remaining);
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

    // 보류 큐가 빈 뒤 RESUMING → OPEN. 06에서 lock 안의 큐 크기 재확인을 추가한다
    public boolean tryCompleteResume() {
        lock.lock();
        try {
            if (phase != WritePhase.RESUMING) {
                return false;
            }
            openedAt = now();
            changePhase(WritePhase.OPEN);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // 진행 중 작업이 0이 될 때까지 기다린다. 시간 안에 0이 되면 true
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long remainingNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        lock.lock();
        try {
            while (!inFlight.isEmpty()) {
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

    // ---- 내부 ----

    private WorkTicket openTicket(WorkType type, String description, Long parentId) {
        WorkTicket ticket = new WorkTicket(nextTicketId++, type, description, parentId, now(), this::release);
        inFlight.put(ticket.id(), ticket);
        return ticket;
    }

    private void release(WorkTicket ticket) {
        lock.lock();
        try {
            if (inFlight.remove(ticket.id(), ticket) && inFlight.isEmpty()) {
                drained.signalAll();
            }
        } finally {
            lock.unlock();
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
        EnumMap<WorkType, Integer> byType = new EnumMap<>(WorkType.class);
        for (WorkType type : WorkType.values()) {
            byType.put(type, 0);
        }
        for (WorkTicket ticket : inFlight.values()) {
            byType.merge(ticket.type(), 1, Integer::sum);
        }
        return new GateSnapshot(
                phase,
                migrationId,
                epoch,
                resumeTarget,
                inFlight.size(),
                byType,
                drainStartedAt,
                frozenAt,
                resumeStartedAt,
                openedAt
        );
    }

    private Instant now() {
        return clock.instant();
    }
}
