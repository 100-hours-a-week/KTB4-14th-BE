package com.audigo.global.migration.ai;

import com.audigo.global.migration.tracking.WorkTicket;
import java.time.Instant;

// 지연이 풀린 AI 작업. 지연 해제와 같은 임계 구역에서 AI_JOB 티켓을 열어 두었으므로 제출 측이 반드시 닫아야 한다
public record ReleasedAiJob(long jobId, Instant deferredAt, WorkTicket ticket) {
}
