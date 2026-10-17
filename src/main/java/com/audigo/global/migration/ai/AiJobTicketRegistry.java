package com.audigo.global.migration.ai;

import com.audigo.global.migration.tracking.WorkTicket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

// AI 작업 티켓을 jobId로 워커에 넘긴다. @Async 메서드 시그니처를 바꾸지 않기 위해 사용한다.
// 같은 jobId가 겹쳐 제출돼도 티켓 수가 맞도록 jobId마다 제출 순서대로 보관한다
@Component
public class AiJobTicketRegistry {

    private final ConcurrentHashMap<Long, Deque<WorkTicket>> tickets = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();

    public void register(long jobId, WorkTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        tickets.compute(jobId, (key, queue) -> {
            Deque<WorkTicket> next = queue == null ? new ArrayDeque<>() : queue;
            next.addLast(ticket);
            return next;
        });
        size.incrementAndGet();
    }

    // 워커 종료 시 호출한다. 닫은 티켓이 있으면 true
    public boolean close(long jobId) {
        AtomicReference<WorkTicket> removed = new AtomicReference<>();
        tickets.computeIfPresent(jobId, (key, queue) -> {
            removed.set(queue.pollFirst());
            return queue.isEmpty() ? null : queue;
        });
        return closeOutsideMap(removed.get());
    }

    // 제출에 실패한 티켓을 정확히 그 티켓만 빼고 닫는다
    public boolean discard(long jobId, WorkTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        AtomicReference<WorkTicket> removed = new AtomicReference<>();
        tickets.computeIfPresent(jobId, (key, queue) -> {
            if (queue.remove(ticket)) {
                removed.set(ticket);
            }
            return queue.isEmpty() ? null : queue;
        });
        return closeOutsideMap(removed.get());
    }

    // 워커에 넘긴 AI 작업 수 (실행 중 + executor 큐 대기)
    public int size() {
        return size.get();
    }

    // close는 Gate lock을 잡으므로 map 갱신 밖에서 호출한다
    private boolean closeOutsideMap(WorkTicket ticket) {
        if (ticket == null) {
            return false;
        }
        size.decrementAndGet();
        ticket.close();
        return true;
    }
}
