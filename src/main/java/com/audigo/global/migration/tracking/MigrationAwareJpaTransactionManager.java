package com.audigo.global.migration.tracking;

import java.util.Objects;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

// 작업 티켓에서 빠진 쓰기도 잡기 위해 트랜잭션 매니저 수준에서 쓰기 트랜잭션을 센다.
// 새 트랜잭션을 실제로 시작할 때만 doBegin이 호출되므로 REQUIRED 참여는 세지 않고 REQUIRES_NEW는 따로 센다
public class MigrationAwareJpaTransactionManager extends JpaTransactionManager {

    private final TransactionWriteObserver observer;

    public MigrationAwareJpaTransactionManager(TransactionWriteObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        boolean write = !definition.isReadOnly();
        if (write) {
            observer.beforeWriteBegin(transaction, definition);
        }
        try {
            super.doBegin(transaction, definition);
        } catch (RuntimeException | Error exception) {
            // doBegin이 실패하면 doCleanupAfterCompletion이 호출되지 않는다
            if (write) {
                observer.afterCompletion(transaction);
            }
            throw exception;
        }
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        try {
            super.doCleanupAfterCompletion(transaction);
        } finally {
            observer.afterCompletion(transaction);
        }
    }
}
