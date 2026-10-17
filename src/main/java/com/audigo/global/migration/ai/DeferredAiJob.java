package com.audigo.global.migration.ai;

import java.time.Instant;

// 시작이 지연된 AI 작업 1건. 쓰기를 하지 않으므로 티켓 없이 jobId만 보관한다
public record DeferredAiJob(long jobId, Instant deferredAt) {
}
