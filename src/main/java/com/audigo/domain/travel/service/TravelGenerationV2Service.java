package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.dto.TravelPlanCreatedResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.messaging.TravelGenerationJobMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelGenerationV2Service {

    private final TravelPlanService travelPlanService;
    private final TravelGenerationRequestFactory requestFactory;
    private final TravelGenerationPayloadStore payloadStore;
    private final TravelGenerationProgressStore progressStore;
    private final TravelGenerationOutboxStore outboxStore;

    public TravelGenerationV2Service(
            TravelPlanService travelPlanService,
            TravelGenerationRequestFactory requestFactory,
            TravelGenerationPayloadStore payloadStore,
            TravelGenerationProgressStore progressStore,
            TravelGenerationOutboxStore outboxStore
    ) {
        this.travelPlanService = travelPlanService;
        this.requestFactory = requestFactory;
        this.payloadStore = payloadStore;
        this.progressStore = progressStore;
        this.outboxStore = outboxStore;
    }

    @Transactional
    public TravelPlanCreatedResponse create(Long userId, TravelPlanRequest request) {
        TravelGenerationJob job = travelPlanService.createTravelPlanWithGenerationJob(userId, request);
        return enqueue(job, request);
    }

    @Transactional
    public TravelPlanCreatedResponse regenerate(Long userId, Long travelPlanId, TravelPlanRequest request) {
        TravelGenerationJob job = travelPlanService.regenerateTravelPlanWithGenerationJob(userId, travelPlanId);
        return enqueue(job, request);
    }

    private TravelPlanCreatedResponse enqueue(TravelGenerationJob job, TravelPlanRequest request) {
        TravelPlan travelPlan = job.getTravelPlan();
        AiTravelGenerationRequest aiRequest = requestFactory.from(travelPlan, request);

        payloadStore.saveRequest(job.getId(), aiRequest);
        progressStore.initialize(job.getId());
        outboxStore.enqueue(job, TravelGenerationJobMessage.create(job.getId(), aiRequest));

        return TravelPlanCreatedResponse.from(travelPlan, job.getId());
    }
}
