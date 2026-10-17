package com.audigo.global.migration.admin;

import com.audigo.global.migration.MigrationErrorEvent;
import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.gate.MigrationWriteGate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 최근 error-history-size개의 컷오버 제어 오류. 단계 전이 시 지우지 않고 migrationId를 함께 남긴다
@Component
public class MigrationErrorLog {

    private final int capacity;
    private final Clock clock;
    private final ObjectProvider<MigrationWriteGate> gateProvider;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public MigrationErrorLog(MigrationProperties properties, Clock clock, ObjectProvider<MigrationWriteGate> gateProvider) {
        this.capacity = properties.errorHistorySize();
        this.clock = clock;
        this.gateProvider = gateProvider;
    }

    public record Entry(Instant at, String code, String detail, String migrationId) {
    }

    @EventListener
    public void onError(MigrationErrorEvent event) {
        // Gate lock을 잡은 채 이 객체의 lock을 기다리는 경로가 있으므로, Gate 조회는 lock 밖에서 끝낸다
        String migrationId = event.migrationId() != null ? event.migrationId() : currentMigrationId();
        Entry entry = new Entry(clock.instant(), event.code(), event.detail(), migrationId);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    // 최신순
    public List<Entry> recent() {
        synchronized (entries) {
            List<Entry> recent = new ArrayList<>(entries.size());
            Iterator<Entry> iterator = entries.descendingIterator();
            while (iterator.hasNext()) {
                recent.add(iterator.next());
            }
            return recent;
        }
    }

    private String currentMigrationId() {
        MigrationWriteGate gate = gateProvider.getIfAvailable();
        return gate == null ? null : gate.snapshot().migrationId();
    }
}
