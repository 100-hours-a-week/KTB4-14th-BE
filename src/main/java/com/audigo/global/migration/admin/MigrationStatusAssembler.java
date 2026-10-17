package com.audigo.global.migration.admin;

import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.ai.AiExecutorMonitor;
import com.audigo.global.migration.ai.AiExecutorStats;
import com.audigo.global.migration.ai.DeferredAiJobStats;
import com.audigo.global.migration.gate.GateSnapshot;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.pending.PendingQueueStats;
import com.audigo.global.migration.pending.PendingWriteDispatcher;
import com.audigo.global.migration.pending.PendingWriteQueue;
import com.audigo.global.migration.pending.ResumeStats;
import com.audigo.global.migration.task.ScheduledTaskGuardAspect;
import com.audigo.global.migration.tracking.WorkTicket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// 각 컴포넌트의 상태를 모아 관리 API 상태 응답을 만든다. 경과 시간은 Clock 빈으로 계산한다
@Component
public class MigrationStatusAssembler {

    private static final int OLDEST_TICKET_LIMIT = 5;

    private final MigrationProperties properties;
    private final MigrationWriteGate gate;
    private final PendingWriteQueue pendingQueue;
    private final PendingWriteDispatcher dispatcher;
    private final AiExecutorMonitor aiExecutorMonitor;
    private final ScheduledTaskGuardAspect scheduledGuard;
    private final MigrationErrorLog errorLog;
    private final Clock clock;
    private final Instant instanceStartedAt;

    public MigrationStatusAssembler(
            MigrationProperties properties,
            MigrationWriteGate gate,
            PendingWriteQueue pendingQueue,
            PendingWriteDispatcher dispatcher,
            AiExecutorMonitor aiExecutorMonitor,
            ScheduledTaskGuardAspect scheduledGuard,
            MigrationErrorLog errorLog,
            Clock clock
    ) {
        this.properties = properties;
        this.gate = gate;
        this.pendingQueue = pendingQueue;
        this.dispatcher = dispatcher;
        this.aiExecutorMonitor = aiExecutorMonitor;
        this.scheduledGuard = scheduledGuard;
        this.errorLog = errorLog;
        this.clock = clock;
        this.instanceStartedAt = clock.instant();
    }

    public MigrationStatusResponse assemble() {
        return assemble(gate.snapshot());
    }

    public MigrationStatusResponse assemble(GateSnapshot snapshot) {
        Instant now = clock.instant();
        return new MigrationStatusResponse(
                properties.instanceId(),
                text(instanceStartedAt),
                properties.enabled(),
                snapshot.migrationId(),
                snapshot.epoch(),
                snapshot.phase().name(),
                snapshot.resumeTarget() == null ? null : snapshot.resumeTarget().name(),
                timeline(snapshot, now),
                inFlight(snapshot, now),
                pendingQueue(),
                deferredAiJobs(snapshot.deferredAiJobs()),
                resume(dispatcher.stats()),
                new MigrationStatusResponse.Scheduled(scheduledGuard.skippedRuns()),
                errorLog.recent().stream()
                        .map(entry -> new MigrationStatusResponse.ErrorItem(
                                text(entry.at()), entry.code(), entry.detail(), entry.migrationId()))
                        .toList()
        );
    }

    public List<MigrationStatusResponse.Ticket> tickets(List<WorkTicket> tickets) {
        Instant now = clock.instant();
        return tickets.stream().map(ticket -> ticket(ticket, now)).toList();
    }

    private MigrationStatusResponse.Timeline timeline(GateSnapshot snapshot, Instant now) {
        Instant phaseEnteredAt = switch (snapshot.phase()) {
            case OPEN -> snapshot.openedAt();
            case DRAINING -> snapshot.drainStartedAt();
            case FROZEN -> snapshot.frozenAt();
            case RESUMING -> snapshot.resumeStartedAt();
        };
        return new MigrationStatusResponse.Timeline(
                text(snapshot.drainStartedAt()),
                text(snapshot.frozenAt()),
                text(snapshot.resumeStartedAt()),
                text(snapshot.openedAt()),
                millisBetween(phaseEnteredAt, now),
                millisBetween(snapshot.drainStartedAt(), snapshot.frozenAt()),
                snapshot.writeStopDuration() == null ? null : snapshot.writeStopDuration().toMillis()
        );
    }

    private MigrationStatusResponse.InFlight inFlight(GateSnapshot snapshot, Instant now) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        snapshot.inFlightByType().forEach((type, count) -> byType.put(type.name(), count));
        List<MigrationStatusResponse.Ticket> oldest = gate.openTickets().stream()
                .limit(OLDEST_TICKET_LIMIT)
                .map(ticket -> ticket(ticket, now))
                .toList();
        AiExecutorStats executor = aiExecutorMonitor.stats();
        return new MigrationStatusResponse.InFlight(
                snapshot.inFlightTotal(),
                byType,
                snapshot.activeWriteTransactions(),
                text(snapshot.lastWriteCompletedAt()),
                snapshot.frozenWriteRejections(),
                oldest,
                new MigrationStatusResponse.AiExecutor(executor.active(), executor.queued(), executor.trackedJobs())
        );
    }

    private MigrationStatusResponse.PendingQueue pendingQueue() {
        PendingQueueStats stats = pendingQueue.stats();
        return new MigrationStatusResponse.PendingQueue(
                stats.size(),
                properties.pendingCapacity(),
                stats.oldestWait().toMillis(),
                new MigrationStatusResponse.Counters(
                        stats.held(), stats.dispatched(), stats.timedOut(), stats.abandoned(), stats.rejected()),
                stats.head().stream()
                        .map(item -> new MigrationStatusResponse.PendingItem(
                                item.id(), item.method(), item.uri(), item.requestId(), item.waited().toMillis()))
                        .toList()
        );
    }

    private static MigrationStatusResponse.DeferredAiJobs deferredAiJobs(DeferredAiJobStats stats) {
        return new MigrationStatusResponse.DeferredAiJobs(stats.enabled(), stats.size(), stats.oldestWait().toMillis());
    }

    private static MigrationStatusResponse.Resume resume(ResumeStats stats) {
        return new MigrationStatusResponse.Resume(
                stats.running(),
                text(stats.startedAt()),
                text(stats.completedAt()),
                stats.dispatched(),
                stats.succeeded(),
                stats.failed(),
                stats.skipped(),
                stats.maxWait().toMillis()
        );
    }

    private static MigrationStatusResponse.Ticket ticket(WorkTicket ticket, Instant now) {
        return new MigrationStatusResponse.Ticket(
                ticket.type().name(),
                ticket.description(),
                Duration.between(ticket.openedAt(), now).toMillis(),
                ticket.threadName()
        );
    }

    private static Long millisBetween(Instant from, Instant to) {
        return from == null || to == null ? null : Duration.between(from, to).toMillis();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
