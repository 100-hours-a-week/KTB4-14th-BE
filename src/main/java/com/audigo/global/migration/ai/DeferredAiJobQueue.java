package com.audigo.global.migration.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// 시작이 지연된 AI 작업 FIFO. 스레드 안전하지 않으며 MigrationWriteGate의 lock 안에서만 호출한다.
// 지연 플래그 확인 + 추가, 플래그 해제 + 전체 꺼내기가 같은 임계 구역에서 일어나야 작업이 큐에 남지 않는다
public final class DeferredAiJobQueue {

    private final Deque<DeferredAiJob> jobs = new ArrayDeque<>();

    public void add(long jobId, Instant deferredAt) {
        jobs.addLast(new DeferredAiJob(jobId, deferredAt));
    }

    // 들어온 순서대로 모두 꺼낸다
    public List<DeferredAiJob> drainAll() {
        List<DeferredAiJob> drained = new ArrayList<>(jobs);
        jobs.clear();
        return drained;
    }

    public int size() {
        return jobs.size();
    }

    public Duration oldestWait(Instant now) {
        DeferredAiJob oldest = jobs.peekFirst();
        return oldest == null ? Duration.ZERO : Duration.between(oldest.deferredAt(), now);
    }
}
