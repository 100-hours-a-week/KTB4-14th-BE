package com.audigo.global.migration.tracking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// 진행 중인 쓰기 트랜잭션 목록. 스레드 안전하지 않으며 MigrationWriteGate의 lock 안에서만 호출한다
public final class ActiveWriteTransactions {

    // 트랜잭션 객체는 equals를 재정의하지 않으므로 identity로 구분한다
    private final Map<Object, ActiveWriteTransaction> active = new IdentityHashMap<>();
    private Instant lastCompletedAt;

    public void begin(Object transaction, String name, Instant startedAt) {
        active.put(transaction, new ActiveWriteTransaction(
                name == null ? "" : name,
                Thread.currentThread().getName(),
                startedAt
        ));
    }

    // 등록된 쓰기 트랜잭션이면 제거하고 true. readOnly 트랜잭션은 등록되지 않으므로 false
    public boolean complete(Object transaction, Instant completedAt) {
        if (active.remove(transaction) == null) {
            return false;
        }
        lastCompletedAt = completedAt;
        return true;
    }

    public int count() {
        return active.size();
    }

    // 오래된 순
    public List<ActiveWriteTransaction> list() {
        List<ActiveWriteTransaction> transactions = new ArrayList<>(active.values());
        transactions.sort((left, right) -> left.startedAt().compareTo(right.startedAt()));
        return transactions;
    }

    public Instant lastCompletedAt() {
        return lastCompletedAt;
    }
}
