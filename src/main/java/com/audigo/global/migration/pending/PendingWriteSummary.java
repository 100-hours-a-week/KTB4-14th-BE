package com.audigo.global.migration.pending;

import java.time.Duration;

// 상태 조회용 보류 요청 요약
public record PendingWriteSummary(
        String id,
        String method,
        String uri,
        String requestId,
        Long userId,
        Duration waited
) {
}
