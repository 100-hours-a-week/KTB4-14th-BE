package com.audigo.global.migration.tracking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 열린 작업 티켓 목록. 스레드 안전하지 않으며 MigrationWriteGate의 lock 안에서만 호출한다
public final class InFlightWorkTracker {

    // 삽입 순서 = 오래된 순
    private final Map<Long, WorkTicket> openTickets = new LinkedHashMap<>();
    private long nextTicketId = 1;

    public WorkTicket open(
            WorkType type,
            String description,
            Long parentId,
            Instant openedAt,
            Consumer<WorkTicket> onClose
    ) {
        WorkTicket ticket = new WorkTicket(nextTicketId++, type, description, parentId, openedAt, onClose);
        openTickets.put(ticket.id(), ticket);
        return ticket;
    }

    // 목록에 있던 티켓이면 제거하고 true
    public boolean release(WorkTicket ticket) {
        return openTickets.remove(ticket.id(), ticket);
    }

    public boolean isOpen(WorkTicket ticket) {
        return !ticket.isClosed() && openTickets.get(ticket.id()) == ticket;
    }

    public int total() {
        return openTickets.size();
    }

    public Map<WorkType, Integer> countByType() {
        EnumMap<WorkType, Integer> counts = new EnumMap<>(WorkType.class);
        for (WorkType type : WorkType.values()) {
            counts.put(type, 0);
        }
        for (WorkTicket ticket : openTickets.values()) {
            counts.merge(ticket.type(), 1, Integer::sum);
        }
        return counts;
    }

    public List<WorkTicket> openTickets() {
        return new ArrayList<>(openTickets.values());
    }
}
