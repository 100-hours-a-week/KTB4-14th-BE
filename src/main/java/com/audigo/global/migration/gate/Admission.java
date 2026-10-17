package com.audigo.global.migration.gate;

import com.audigo.global.migration.tracking.WorkTicket;

// 작업 입장 판정 결과
public sealed interface Admission {

    // 실행하고 작업이 끝나면 티켓을 닫는다
    record Admitted(WorkTicket ticket) implements Admission {
    }

    // 보류 큐로 보낸다 (HTTP)
    record Hold() implements Admission {
    }

    // 이번 실행을 건너뛴다 (@Scheduled)
    record Skip() implements Admission {
    }
}
