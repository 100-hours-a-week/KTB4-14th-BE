package com.audigo.global.migration.admin;

import java.util.List;

// 관리 명령 결과. outcome은 APPLIED / IDEMPOTENT / REJECTED
// freeze가 잔여 작업으로 거부되면 남은 티켓과 쓰기 트랜잭션을 함께 담는다
public record MigrationCommandResult(
        String outcome,
        String failure,
        MigrationStatusResponse status,
        List<MigrationStatusResponse.Ticket> remainingWork,
        List<RemainingTransaction> remainingTransactions
) {

    public record RemainingTransaction(String name, String thread, String startedAt) {
    }
}
