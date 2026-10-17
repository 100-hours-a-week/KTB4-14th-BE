package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.global.migration.MigrationErrorEvent;
import com.audigo.global.migration.ai.AiJobTicketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

//요청 스레드와 AI SSE 스트림 처리를 분리하는 작업 실행기
@Service
public class TravelGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationWorker.class);

    private final AiGenerationClient aiGenerationClient;
    private final TravelGenerationJobService jobService;
    private final TravelGenerationContextStore contextStore;
    private final AiJobTicketRegistry ticketRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public TravelGenerationWorker(
            AiGenerationClient aiGenerationClient,
            TravelGenerationJobService jobService,
            TravelGenerationContextStore contextStore,
            AiJobTicketRegistry ticketRegistry,
            ApplicationEventPublisher eventPublisher
    ) {
        this.aiGenerationClient = aiGenerationClient;
        this.jobService = jobService;
        this.contextStore = contextStore;
        this.ticketRegistry = ticketRegistry;
        this.eventPublisher = eventPublisher;
    }

    // finishStream·markFailed는 @Transactional public 메서드라 반환 시점에 커밋이 끝난다.
    // 따라서 finally 도달 = 마지막 저장 완료이며, 이때 AI_JOB 티켓을 닫는다
    @Async("travelGenerationExecutor")
    public void run(Long jobId) {
        try {
            generate(jobId);
        } finally {
            contextStore.remove(jobId);
            ticketRegistry.close(jobId);
        }
    }

    private void generate(Long jobId) {
        try {
            AiTravelGenerationRequest request = contextStore.get(jobId);
            if (request == null) {
                markFailed(jobId, "여행 생성에 필요한 요청 정보가 없습니다.");
                return;
            }
            jobService.markStarted(jobId);
            aiGenerationClient.stream(request, event -> {
                try {
                    jobService.handleEvent(jobId, event);
                } catch (RuntimeException exception) {
                    log.warn("AI 생성 이벤트 처리에 실패했습니다. jobId={}", jobId, exception);
                    jobService.markFailed(jobId, "AI 생성 결과 처리 중 오류가 발생했습니다.");
                }
            });
            jobService.finishStream(jobId);
        } catch (RuntimeException exception) {
            log.warn("AI 생성 작업에 실패했습니다. jobId={}", jobId, exception);
            markFailed(jobId, "AI 서버와 통신하는 중 오류가 발생했습니다.");
        }
    }

    // 실패 저장마저 실패하면 job이 GENERATING으로 남는다. 다음 OPEN 이후 watchdog이 실패 처리한다
    private void markFailed(Long jobId, String message) {
        try {
            jobService.markFailed(jobId, message);
        } catch (RuntimeException exception) {
            log.error("migration_ai_job_final_save_failed job_id={}", jobId, exception);
            eventPublisher.publishEvent(new MigrationErrorEvent(
                    "migration_ai_job_final_save_failed", "job_id=" + jobId + " " + exception.getClass().getSimpleName()));
        }
    }
}
