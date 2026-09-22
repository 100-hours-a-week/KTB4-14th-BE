package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.dto.TravelGenerationStatusResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 여행 생성 요청과 AI SSE 작업 사이의 조정 계층
@Service
public class TravelGenerationOrchestrator {

    private final TravelPlanRepository travelPlanRepository;
    private final TravelGenerationJobService jobService;
    private final TravelGenerationRequestFactory requestFactory;
    private final TravelGenerationContextStore contextStore;
    private final TravelGenerationProgressStore progressStore;
    private final TravelGenerationWorker worker;

    public TravelGenerationOrchestrator(
            TravelPlanRepository travelPlanRepository,
            TravelGenerationJobService jobService,
            TravelGenerationRequestFactory requestFactory,
            TravelGenerationContextStore contextStore,
            TravelGenerationProgressStore progressStore,
            TravelGenerationWorker worker
    ) {
        this.travelPlanRepository = travelPlanRepository;
        this.jobService = jobService;
        this.requestFactory = requestFactory;
        this.contextStore = contextStore;
        this.progressStore = progressStore;
        this.worker = worker;
    }

    @Transactional(readOnly = true)
    public Long schedule(Long userId, Long travelPlanId, TravelPlanRequest request) {
        TravelGenerationJob job = jobService.findLatestJob(userId, travelPlanId);
        return schedule(job, request);
    }

    @Transactional(readOnly = true)
    public Long scheduleJob(Long userId, Long jobId, TravelPlanRequest request) {
        TravelGenerationJob job = jobService.findOwnedJob(userId, jobId);
        return schedule(job, request);
    }

    @Transactional(readOnly = true)
    public TravelGenerationStatusResponse status(Long userId, Long travelPlanId) {
        return jobService.status(jobService.findLatestJob(userId, travelPlanId).getId());
    }

    @Transactional(readOnly = true)
    public TravelGenerationStatusResponse statusByJob(Long userId, Long jobId) {
        jobService.findOwnedJob(userId, jobId);
        return jobService.status(jobId);
    }

    private Long schedule(TravelGenerationJob job, TravelPlanRequest request) {
        if (job.getStatus() != com.audigo.domain.travel.entity.TravelPlanStatus.GENERATING) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var plan = travelPlanRepository.findById(job.getTravelPlan().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        AiTravelGenerationRequest aiRequest = requestFactory.from(plan, request);
        contextStore.put(job.getId(), aiRequest);
        progressStore.initialize(job.getId());
        worker.run(job.getId());
        return job.getId();
    }
}
