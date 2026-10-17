package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.TravelGenerationExecutionStatus;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.messaging.TravelGenerationMessageVersion;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.messaging.TravelGenerationResultType;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelGenerationJobServiceTest {

    @Mock
    private TravelGenerationJobRepository jobRepository;

    @Mock
    private TravelItineraryPersistenceService persistenceService;

    @Mock
    private TravelGenerationPayloadStore payloadStore;

    @Mock
    private NotificationService notificationService;

    private TravelGenerationProgressStore progressStore;
    private TravelGenerationJobService service;
    private TravelGenerationJob job;
    private TravelPlan plan;
    private Region region;

    @BeforeEach
    void setUp() {
        progressStore = new InMemoryTravelGenerationProgressStore();
        service = new TravelGenerationJobService(
                jobRepository,
                progressStore,
                payloadStore,
                persistenceService,
                notificationService,
                new ObjectMapper()
        );
        job = mock(TravelGenerationJob.class);
        plan = mock(TravelPlan.class);
        region = mock(Region.class);
        lenient().when(job.getId()).thenReturn(1L);
        lenient().when(job.getStatus()).thenReturn(TravelPlanStatus.GENERATING);
        lenient().when(job.getTravelPlan()).thenReturn(plan);
        lenient().when(plan.getId()).thenReturn(55L);
        lenient().when(plan.getUserId()).thenReturn(1L);
        lenient().when(plan.getRegion()).thenReturn(region);
        lenient().when(region.getFullName()).thenReturn("서울특별시");
        lenient().when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        lenient().when(jobRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        progressStore.initialize(1L);
    }

    @Test
    void 숙소_추천_전에_도착한_경로_이벤트는_여행을_실패시킨다() {
        service.handleEvent(1L, new AiGenerationEvent("ROUTE_OPTIMIZE_DONE", "{}"));

        verify(job).fail("AI 생성 단계 순서가 올바르지 않습니다.");
        verify(plan).markFailed();
        verify(notificationService).notifyTravelFailed(1L, 55L, "AI 생성 단계 순서가 올바르지 않습니다.");
        verify(persistenceService, never()).persistIfPresent(any());
        assertThat(progressStore.states(1L)
                .get(com.audigo.domain.travel.entity.TravelGenerationStage.PLACE_RECOMMEND))
                .isEqualTo(com.audigo.domain.travel.entity.TravelGenerationStageState.FAILED);
    }

    @Test
    void 장소_추천_전에_도착한_숙소_이벤트는_여행을_실패시킨다() {
        service.handleEvent(1L, new AiGenerationEvent("STAY_RECOMMEND_START", "{}"));

        verify(job).fail("AI 생성 단계 순서가 올바르지 않습니다.");
        verify(plan).markFailed();
        verify(notificationService).notifyTravelFailed(1L, 55L, "AI 생성 단계 순서가 올바르지 않습니다.");
        verify(persistenceService, never()).persistIfPresent(any());
    }

    @Test
    void 단계_이벤트_뒤_COMPLETE가_도착하면_일정을_저장하고_완료한다() {
        when(persistenceService.persistIfPresent(job)).thenReturn(false, true);

        service.handleEvent(1L, new AiGenerationEvent("PLACE_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent("STAY_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent(
                "ROUTE_OPTIMIZE_DONE",
                "{}"
        ));

        verify(job, never()).fail(anyString());
        verify(job, never()).complete();

        service.handleEvent(1L, new AiGenerationEvent(
                "COMPLETE",
                "{\"days\":[{\"day_number\":1,\"items\":[]}]}"
        ));

        verify(persistenceService, org.mockito.Mockito.times(2)).persistIfPresent(job);
        verify(job).complete();
        verify(plan).markCompleted();
        verify(jobRepository).save(job);
        verify(notificationService).notifyTravelComplete(1L, 55L, "서울특별시 여행 일정이 완성됐어요.");
    }

    @Test
    void COMPLETE없이_스트림이_종료되면_여행을_실패시킨다() {
        service.handleEvent(1L, new AiGenerationEvent("PLACE_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent("STAY_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent("ROUTE_OPTIMIZE_DONE", "{}"));

        verify(job, never()).fail(anyString());

        service.finishStream(1L);

        verify(job).fail("AI 서버가 여행 생성 결과를 모두 보내지 않았습니다.");
        verify(plan).markFailed();
        verify(jobRepository).save(job);
        verify(notificationService).notifyTravelFailed(1L, 55L, "AI 서버가 여행 생성 결과를 모두 보내지 않았습니다.");
    }

    @Test
    void 제한시간을_넘은_PROCESSING_작업은_실패시키고_알림을_보낸다() {
        when(jobRepository.findByExecutionStatusAndStartedAtBefore(
                org.mockito.Mockito.eq(TravelGenerationExecutionStatus.PROCESSING),
                org.mockito.Mockito.any(LocalDateTime.class)
        )).thenReturn(List.of(job));

        int count = service.failStaleGeneratingJobs(Duration.ofMinutes(5));

        assertThat(count).isEqualTo(1);
        verify(jobRepository).findByExecutionStatusAndStartedAtBefore(
                org.mockito.Mockito.eq(TravelGenerationExecutionStatus.PROCESSING),
                org.mockito.Mockito.any(LocalDateTime.class)
        );
        verify(job).fail("AI 서버 응답이 지연되어 여행 일정 생성에 실패했습니다.");
        verify(plan).markFailed();
        verify(jobRepository).save(job);
        verify(notificationService).notifyTravelFailed(
                1L,
                55L,
                "AI 서버 응답이 지연되어 여행 일정 생성에 실패했습니다."
        );
    }

    @Test
    void 전체_여행_응답이_COMPLETE_이벤트로_오면_단계별_이벤트가_없어도_완료한다() {
        when(persistenceService.persistIfPresent(job)).thenReturn(true);

        service.handleEvent(1L, new AiGenerationEvent(
                "COMPLETE",
                "{\"travel_plan_id\":55,\"days\":[{\"day_number\":1,\"items\":[]}] }"
        ));

        verify(persistenceService).persistIfPresent(job);
        verify(job).complete();
        verify(plan).markCompleted();
        verify(jobRepository).save(job);
        verify(notificationService).notifyTravelComplete(1L, 55L, "서울특별시 여행 일정이 완성됐어요.");
        assertThat(progressStore.states(1L).values())
                .allMatch(state -> state == com.audigo.domain.travel.entity.TravelGenerationStageState.DONE
                        || state == com.audigo.domain.travel.entity.TravelGenerationStageState.PENDING);
    }

    @Test
    void Result_PROGRESS는_작업을_시작하고_단계_상태를_반영한다() {
        when(job.getExecutionStatus()).thenReturn(TravelGenerationExecutionStatus.QUEUED);

        service.handleResultMessage(resultMessage(
                TravelGenerationResultType.PROGRESS,
                TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.RUNNING,
                null,
                null
        ));

        verify(job).markStarted();
        verify(plan).restartGeneration();
        assertThat(progressStore.states(1L).get(TravelGenerationStage.PLACE_RECOMMEND))
                .isEqualTo(TravelGenerationStageState.RUNNING);
    }

    @Test
    void Result_COMPLETED는_결과를_저장하고_여행을_완료한다() throws Exception {
        when(job.getExecutionStatus()).thenReturn(TravelGenerationExecutionStatus.PROCESSING);
        when(persistenceService.persistIfPresent(job)).thenReturn(true);
        JsonNode payload = new ObjectMapper().readTree(
                "{\"travel_plan_id\":55,\"days\":[{\"day_number\":1,\"items\":[]}]}"
        );

        service.handleResultMessage(resultMessage(
                TravelGenerationResultType.COMPLETED,
                TravelGenerationStage.ROUTE_OPTIMIZE,
                TravelGenerationStageState.DONE,
                payload,
                null
        ));

        verify(payloadStore).saveResult(1L, payload.toString());
        verify(persistenceService).persistIfPresent(job);
        verify(job).complete();
        verify(plan).markCompleted();
        verify(notificationService).notifyTravelComplete(1L, 55L, "서울특별시 여행 일정이 완성됐어요.");
    }

    @Test
    void Result_FAILED는_작업과_여행을_실패시킨다() {
        when(job.getExecutionStatus()).thenReturn(TravelGenerationExecutionStatus.PROCESSING);

        service.handleResultMessage(resultMessage(
                TravelGenerationResultType.FAILED,
                null,
                null,
                null,
                "AI 생성 실패"
        ));

        verify(job).fail("AI 생성 실패");
        verify(plan).markFailed();
        verify(notificationService).notifyTravelFailed(1L, 55L, "AI 생성 실패");
    }

    @Test
    void Result_PROGRESS는_Standard_Queue에서_단계_순서가_바뀌어도_실패하지_않는다() {
        when(job.getExecutionStatus()).thenReturn(TravelGenerationExecutionStatus.PROCESSING);

        service.handleResultMessage(resultMessage(
                TravelGenerationResultType.PROGRESS,
                TravelGenerationStage.STAY_RECOMMEND,
                TravelGenerationStageState.DONE,
                null,
                null
        ));

        verify(job, never()).fail(anyString());
        assertThat(progressStore.states(1L).get(TravelGenerationStage.STAY_RECOMMEND))
                .isEqualTo(TravelGenerationStageState.DONE);
    }

    @Test
    void 늦게_도착한_RUNNING은_DONE_상태를_되돌리지_않는다() {
        when(job.getExecutionStatus()).thenReturn(TravelGenerationExecutionStatus.PROCESSING);
        progressStore.update(1L, TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.DONE, null);

        service.handleResultMessage(resultMessage(
                TravelGenerationResultType.PROGRESS,
                TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.RUNNING,
                null,
                null
        ));

        assertThat(progressStore.states(1L).get(TravelGenerationStage.PLACE_RECOMMEND))
                .isEqualTo(TravelGenerationStageState.DONE);
    }

    @Test
    void 지원하지_않는_Result_메시지_버전은_처리하지_않는다() {
        TravelGenerationResultMessage message = new TravelGenerationResultMessage(
                "event-1",
                1L,
                "999",
                Instant.parse("2026-10-17T01:00:00Z"),
                TravelGenerationResultType.PROGRESS,
                TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.RUNNING,
                null,
                null
        );

        assertThatThrownBy(() -> service.handleResultMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("버전");
    }

    private TravelGenerationResultMessage resultMessage(
            TravelGenerationResultType type,
            TravelGenerationStage stage,
            TravelGenerationStageState state,
            JsonNode payload,
            String errorMessage
    ) {
        return new TravelGenerationResultMessage(
                "event-1",
                1L,
                TravelGenerationMessageVersion.V1,
                Instant.parse("2026-10-17T01:00:00Z"),
                type,
                stage,
                state,
                payload,
                errorMessage
        );
    }
}
