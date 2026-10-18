package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

//요청 스레드와 AI SSE 스트림 처리를 분리하는 작업 실행기
@Service
public class TravelGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationWorker.class);

    private final AiGenerationClient aiGenerationClient;
    private final TravelGenerationJobService jobService;
    private final TravelGenerationContextStore contextStore;

    public TravelGenerationWorker(
            AiGenerationClient aiGenerationClient,
            TravelGenerationJobService jobService,
            TravelGenerationContextStore contextStore
    ) {
        this.aiGenerationClient = aiGenerationClient;
        this.jobService = jobService;
        this.contextStore = contextStore;
    }

    @Async("travelGenerationExecutor")
    public void run(Long jobId) {
        try {
            AiTravelGenerationRequest request = contextStore.get(jobId);
            if (request == null) {
                jobService.markFailed(jobId, "여행 생성에 필요한 요청 정보가 없습니다.");
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
            jobService.markFailed(jobId, "AI 서버와 통신하는 중 오류가 발생했습니다.");
        } finally {
            contextStore.remove(jobId);
        }
    }
}
