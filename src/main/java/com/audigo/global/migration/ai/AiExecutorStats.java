package com.audigo.global.migration.ai;

// AI 생성 executor 상태. 티켓 수(AiJobTicketRegistry.size)와 비교해 추적 누락을 확인한다
public record AiExecutorStats(int active, int queued, int poolSize, int trackedJobs) {
}
