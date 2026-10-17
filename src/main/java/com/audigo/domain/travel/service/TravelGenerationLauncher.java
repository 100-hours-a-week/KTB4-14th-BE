package com.audigo.domain.travel.service;

import com.audigo.global.migration.ai.AiJobTicketRegistry;
import com.audigo.global.migration.ai.DeferredAiJobsReleasedEvent;
import com.audigo.global.migration.ai.ReleasedAiJob;
import com.audigo.global.migration.gate.AiJobStart;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.tracking.HttpWorkTickets;
import com.audigo.global.migration.tracking.WorkTicket;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// AI 워커 제출 지점. 제출 전에 AI_JOB 티켓을 열어 워커에 넘기고, AI 시작 지연 중이면 제출을 OPEN 이후로 미룬다
@Component
public class TravelGenerationLauncher {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationLauncher.class);

    private final MigrationWriteGate gate;
    private final AiJobTicketRegistry ticketRegistry;
    private final TravelGenerationWorker worker;
    private final TravelGenerationJobService jobService;
    private final TravelGenerationContextStore contextStore;

    public TravelGenerationLauncher(
            MigrationWriteGate gate,
            AiJobTicketRegistry ticketRegistry,
            TravelGenerationWorker worker,
            TravelGenerationJobService jobService,
            TravelGenerationContextStore contextStore
    ) {
        this.gate = gate;
        this.ticketRegistry = ticketRegistry;
        this.worker = worker;
        this.jobService = jobService;
        this.contextStore = contextStore;
    }

    // 생성 요청 처리 중 호출된다. 요청의 HTTP 티켓이 열려 있는 동안 AI_JOB 티켓을 연다
    public void launch(Long jobId) {
        AiJobStart start = gate.admitAiJob(HttpWorkTickets.current(), jobId, description(jobId));
        if (start instanceof AiJobStart.Run run) {
            submit(jobId, run.ticket());
            return;
        }
        // 지연된 작업은 쓰기를 하지 않으므로 티켓 없이 대기한다. contextStore의 요청 정보는 그대로 둔다
        log.info("travel_generation_deferred job_id={}", jobId);
    }

    // OPEN 도달 시 지연된 순서대로 제출한다. executor 크기가 동시 실행 수를 제한한다
    @EventListener
    public void onDeferredJobsReleased(DeferredAiJobsReleasedEvent event) {
        Instant now = Instant.now();
        for (ReleasedAiJob job : event.jobs()) {
            startReleased(job, now);
        }
    }

    private void startReleased(ReleasedAiJob job, Instant now) {
        long jobId = job.jobId();
        try {
            jobService.restartClock(jobId);
        } catch (RuntimeException exception) {
            log.error("migration_deferred_ai_job_start_failed job_id={} reason=restart_clock", jobId, exception);
            gate.reportError("migration_deferred_ai_job_start_failed", "job_id=" + jobId + " reason=restart_clock");
            job.ticket().close();
            abandon(jobId, "AI 생성 작업을 시작하지 못했습니다.");
            return;
        }
        try {
            submit(jobId, job.ticket());
            log.info("travel_generation_deferred_started job_id={} deferred_ms={}",
                    jobId, Duration.between(job.deferredAt(), now).toMillis());
        } catch (RuntimeException exception) {
            log.error("migration_deferred_ai_job_start_failed job_id={} reason=submit", jobId, exception);
            gate.reportError("migration_deferred_ai_job_start_failed", "job_id=" + jobId + " reason=submit");
            abandon(jobId, "AI 생성 작업을 시작하지 못했습니다.");
        }
    }

    // 제출에 실패(TaskRejectedException 등)하면 워커의 finally가 실행되지 않으므로 여기서 티켓을 닫는다.
    // 이미 워커가 닫은 티켓이면 discard는 아무것도 하지 않는다
    private void submit(long jobId, WorkTicket ticket) {
        ticketRegistry.register(jobId, ticket);
        try {
            worker.run(jobId);
        } catch (RuntimeException exception) {
            ticketRegistry.discard(jobId, ticket);
            throw exception;
        }
    }

    private void abandon(long jobId, String message) {
        contextStore.remove(jobId);
        try {
            jobService.markFailed(jobId, message);
        } catch (RuntimeException exception) {
            log.error("migration_ai_job_final_save_failed job_id={}", jobId, exception);
            gate.reportError("migration_ai_job_final_save_failed", "job_id=" + jobId);
        }
    }

    private static String description(long jobId) {
        return "ai-job:" + jobId;
    }
}
