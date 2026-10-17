package com.audigo.global.migration.gate;

import com.audigo.global.migration.tracking.WorkTicket;

// AI 작업 시작 판정 결과
public sealed interface AiJobStart {

    // 워커에 제출하고 워커가 끝나면 티켓을 닫는다
    record Run(WorkTicket ticket) implements AiJobStart {
    }

    // 지연 큐에 들어갔다. 쓰기 단계가 OPEN에 도달하면 DeferredAiJobsReleasedEvent로 다시 전달된다
    record Deferred() implements AiJobStart {
    }
}
