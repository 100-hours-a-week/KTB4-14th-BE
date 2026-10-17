package com.audigo.global.migration.gate;

import com.audigo.global.migration.tracking.ActiveWriteTransaction;
import com.audigo.global.migration.tracking.WorkTicket;
import java.util.List;

public record TransitionResult(
        Outcome outcome,
        TransitionFailure failure,
        GateSnapshot snapshot,
        List<WorkTicket> remainingWork,
        List<ActiveWriteTransaction> remainingTransactions
) {

    public enum Outcome {
        // 단계가 바뀜
        APPLIED,
        // 이미 적용된 명령. 현재 상태를 그대로 반환
        IDEMPOTENT,
        // 거부. 상태는 바뀌지 않음
        REJECTED
    }

    public TransitionResult {
        remainingWork = remainingWork == null ? List.of() : List.copyOf(remainingWork);
        remainingTransactions = remainingTransactions == null ? List.of() : List.copyOf(remainingTransactions);
    }

    static TransitionResult applied(GateSnapshot snapshot) {
        return new TransitionResult(Outcome.APPLIED, null, snapshot, List.of(), List.of());
    }

    static TransitionResult idempotent(GateSnapshot snapshot) {
        return new TransitionResult(Outcome.IDEMPOTENT, null, snapshot, List.of(), List.of());
    }

    static TransitionResult rejected(TransitionFailure failure, GateSnapshot snapshot) {
        return new TransitionResult(Outcome.REJECTED, failure, snapshot, List.of(), List.of());
    }

    static TransitionResult remainingWork(
            GateSnapshot snapshot,
            List<WorkTicket> remainingWork,
            List<ActiveWriteTransaction> remainingTransactions
    ) {
        return new TransitionResult(
                Outcome.REJECTED,
                TransitionFailure.REMAINING_WORK,
                snapshot,
                remainingWork,
                remainingTransactions
        );
    }

    public boolean isSuccess() {
        return outcome != Outcome.REJECTED;
    }
}
