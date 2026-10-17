package com.audigo.global.migration.gate;

import com.audigo.global.migration.ai.DeferredAiJobStats;
import com.audigo.global.migration.tracking.WorkType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

// 상태 조회용 불변 스냅샷. migrationId·epoch는 마지막으로 적용한 명령 기준이다
public record GateSnapshot(
        WritePhase phase,
        String migrationId,
        long epoch,
        ResumeTarget resumeTarget,
        int inFlightTotal,
        Map<WorkType, Integer> inFlightByType,
        int activeWriteTransactions,
        Instant lastWriteCompletedAt,
        Instant drainStartedAt,
        Instant frozenAt,
        Instant resumeStartedAt,
        Instant openedAt,
        DeferredAiJobStats deferredAiJobs
) {

    public GateSnapshot {
        inFlightByType = Map.copyOf(inFlightByType);
    }

    // 이 인스턴스의 쓰기 중단 시간 (freeze 확정 ~ resume 시작). 아직 측정 불가면 null
    public Duration writeStopDuration() {
        if (frozenAt == null || resumeStartedAt == null) {
            return null;
        }
        return Duration.between(frozenAt, resumeStartedAt);
    }
}
