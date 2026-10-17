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
import com.audigo.domain.travel.messaging.TravelGenerationJobMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelGenerationV2ServiceTest {

    @Mock
    private TravelPlanService travelPlanService;
    @Mock
    private TravelGenerationRequestFactory requestFactory;
    @Mock
    private TravelGenerationPayloadStore payloadStore;
    @Mock
    private TravelGenerationProgressStore progressStore;
    @Mock
    private TravelGenerationOutboxStore outboxStore;

    @Test
    void 여행과_작업_payload_Outbox를_한_접수_흐름에서_저장한다() {
        TravelPlanRequest request = mock(TravelPlanRequest.class);
        TravelPlan travelPlan = mock(TravelPlan.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        AiTravelGenerationRequest aiRequest = aiRequest();
        when(travelPlanService.createTravelPlanWithGenerationJob(1L, request)).thenReturn(job);
        when(job.getTravelPlan()).thenReturn(travelPlan);
        when(job.getId()).thenReturn(41L);
        when(travelPlan.getId()).thenReturn(10L);
        when(travelPlan.getStatus()).thenReturn(TravelPlanStatus.GENERATING);
        when(requestFactory.from(travelPlan, request)).thenReturn(aiRequest);
        TravelGenerationV2Service service = new TravelGenerationV2Service(
                travelPlanService,
                requestFactory,
                payloadStore,
                progressStore,
                outboxStore
        );

        var response = service.create(1L, request);

        assertThat(response.travelPlanId()).isEqualTo(10L);
        assertThat(response.generationJobId()).isEqualTo(41L);
        verify(payloadStore).saveRequest(41L, aiRequest);
        verify(progressStore).initialize(41L);
        ArgumentCaptor<TravelGenerationJobMessage> messageCaptor =
                ArgumentCaptor.forClass(TravelGenerationJobMessage.class);
        verify(outboxStore).enqueue(org.mockito.ArgumentMatchers.eq(job), messageCaptor.capture());
        assertThat(messageCaptor.getValue().generationJobId()).isEqualTo(41L);
        assertThat(messageCaptor.getValue().request()).isEqualTo(aiRequest);
    }

    @Test
    void 재생성도_내부_worker가_아닌_Outbox로_접수한다() {
        TravelPlan travelPlan = mock(TravelPlan.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        AiTravelGenerationRequest aiRequest = aiRequest();
        when(travelPlanService.regenerateTravelPlanWithGenerationJob(1L, 10L)).thenReturn(job);
        when(job.getTravelPlan()).thenReturn(travelPlan);
        when(job.getId()).thenReturn(42L);
        when(travelPlan.getId()).thenReturn(10L);
        when(travelPlan.getStatus()).thenReturn(TravelPlanStatus.GENERATING);
        when(requestFactory.from(travelPlan, null)).thenReturn(aiRequest);
        TravelGenerationV2Service service = new TravelGenerationV2Service(
                travelPlanService,
                requestFactory,
                payloadStore,
                progressStore,
                outboxStore
        );

        var response = service.regenerate(1L, 10L, null);

        assertThat(response.travelPlanId()).isEqualTo(10L);
        assertThat(response.generationJobId()).isEqualTo(42L);
        verify(payloadStore).saveRequest(42L, aiRequest);
        verify(progressStore).initialize(42L);
        ArgumentCaptor<TravelGenerationJobMessage> messageCaptor =
                ArgumentCaptor.forClass(TravelGenerationJobMessage.class);
        verify(outboxStore).enqueue(org.mockito.ArgumentMatchers.eq(job), messageCaptor.capture());
        assertThat(messageCaptor.getValue().generationJobId()).isEqualTo(42L);
    }

    private AiTravelGenerationRequest aiRequest() {
        return new AiTravelGenerationRequest(
                10L,
                1L,
                "서울특별시",
                LocalDateTime.of(2026, 10, 20, 10, 0),
                LocalDateTime.of(2026, 10, 22, 18, 0),
                2,
                "FRIEND",
                null,
                List.of()
        );
    }
}
