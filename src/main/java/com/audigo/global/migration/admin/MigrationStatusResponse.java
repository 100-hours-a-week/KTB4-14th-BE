package com.audigo.global.migration.admin;

import java.util.List;
import java.util.Map;

// 인스턴스 컷오버 상태. 시각은 ISO-8601 문자열, 기간은 ms. 개인정보(userId)는 넣지 않는다
public record MigrationStatusResponse(
        String instanceId,
        String instanceStartedAt,
        boolean migrationEnabled,
        String migrationId,
        long epoch,
        String phase,
        String resumeTarget,
        Timeline timeline,
        InFlight inFlight,
        PendingQueue pendingQueue,
        DeferredAiJobs deferredAiJobs,
        Resume resume,
        Scheduled scheduled,
        List<ErrorItem> errors
) {

    public record Timeline(
            String drainStartedAt,
            String frozenAt,
            String resumeStartedAt,
            String openedAt,
            Long elapsedInPhaseMs,
            Long drainDurationMs,
            Long writeStopDurationMs
    ) {
    }

    public record InFlight(
            int total,
            Map<String, Integer> byType,
            int activeWriteTransactions,
            String lastWriteCompletedAt,
            // FROZEN 중 거부한 쓰기 트랜잭션 누적 수. 0이 아니면 통제 누락
            long frozenWriteRejections,
            List<Ticket> oldest,
            AiExecutor aiExecutor
    ) {
    }

    public record Ticket(String type, String description, long elapsedMs, String thread) {
    }

    public record AiExecutor(int active, int queued, int trackedJobs) {
    }

    public record PendingQueue(
            int size,
            int capacity,
            long oldestWaitMs,
            Counters counters,
            List<PendingItem> head
    ) {
    }

    public record Counters(long held, long dispatched, long timedOut, long abandoned, long rejected) {
    }

    public record PendingItem(String id, String method, String uri, String requestId, long waitMs) {
    }

    public record DeferredAiJobs(boolean enabled, int size, long oldestWaitMs) {
    }

    public record Resume(
            boolean running,
            String startedAt,
            String completedAt,
            long dispatched,
            long succeeded,
            long failed,
            long skipped,
            long maxWaitMs
    ) {
    }

    public record Scheduled(long skippedRuns) {
    }

    public record ErrorItem(String at, String code, String detail, String migrationId) {
    }
}
