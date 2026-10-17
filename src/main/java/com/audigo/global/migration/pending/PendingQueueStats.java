package com.audigo.global.migration.pending;

import java.time.Duration;
import java.util.List;

// 보류 큐 상태. 누적 카운터는 인스턴스 기동 이후 합계다
public record PendingQueueStats(
        int size,
        Duration oldestWait,
        long held,
        long dispatched,
        long timedOut,
        long abandoned,
        long rejected,
        List<PendingWriteSummary> head
) {

    public PendingQueueStats {
        head = List.copyOf(head);
    }
}
