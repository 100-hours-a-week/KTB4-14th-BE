package com.audigo.global.migration.pending;

import java.time.Duration;
import java.time.Instant;

// 마지막 재개 실행의 지표. 재개 전이면 시각은 null, 카운터는 0
// skipped: 재개 실행 동안 시간 초과·연결 종료로 실행하지 않고 빠진 보류 요청 수
public record ResumeStats(
        boolean running,
        Instant startedAt,
        Instant completedAt,
        long dispatched,
        long succeeded,
        long failed,
        long skipped,
        Duration maxWait
) {
}
