package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TravelGenerationOrchestratorTest {

    @Test
    void v1_접수는_기존_내부_worker를_실행한다() {
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelGenerationJobService jobService = mock(TravelGenerationJobService.class);
        TravelGenerationRequestFactory requestFactory = mock(TravelGenerationRequestFactory.class);
        TravelGenerationPayloadStore payloadStore = mock(TravelGenerationPayloadStore.class);
        TravelGenerationProgressStore progressStore = mock(TravelGenerationProgressStore.class);
        TravelGenerationWorker worker = mock(TravelGenerationWorker.class);
        TravelPlanRequest request = mock(TravelPlanRequest.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        TravelPlan travelPlan = mock(TravelPlan.class);
        AiTravelGenerationRequest aiRequest = mock(AiTravelGenerationRequest.class);
        when(jobService.findLatestJob(1L, 10L)).thenReturn(job);
        when(job.getId()).thenReturn(41L);
        when(job.getStatus()).thenReturn(TravelPlanStatus.GENERATING);
        when(job.getTravelPlan()).thenReturn(travelPlan);
        when(travelPlan.getId()).thenReturn(10L);
        when(travelPlanRepository.findById(10L)).thenReturn(Optional.of(travelPlan));
        when(requestFactory.from(travelPlan, request)).thenReturn(aiRequest);
        TravelGenerationOrchestrator orchestrator = new TravelGenerationOrchestrator(
                travelPlanRepository,
                jobService,
                requestFactory,
                payloadStore,
                progressStore,
                worker
        );

        Long jobId = orchestrator.schedule(1L, 10L, request);

        assertThat(jobId).isEqualTo(41L);
        verify(payloadStore).saveRequest(41L, aiRequest);
        verify(progressStore).initialize(41L);
        verify(worker).run(41L);
    }
}
