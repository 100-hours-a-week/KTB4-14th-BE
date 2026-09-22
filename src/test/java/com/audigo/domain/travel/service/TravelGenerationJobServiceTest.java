package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private TravelGenerationProgressStore progressStore;
    private TravelGenerationJobService service;
    private TravelGenerationJob job;
    private TravelPlan plan;

    @BeforeEach
    void setUp() {
        progressStore = new TravelGenerationProgressStore();
        service = new TravelGenerationJobService(
                jobRepository,
                progressStore,
                persistenceService,
                new ObjectMapper()
        );
        job = mock(TravelGenerationJob.class);
        plan = mock(TravelPlan.class);
        when(job.getId()).thenReturn(1L);
        when(job.getStatus()).thenReturn(TravelPlanStatus.GENERATING);
        when(job.getTravelPlan()).thenReturn(plan);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        progressStore.initialize(1L);
    }

    @Test
    void 숙소_추천_전에_도착한_경로_이벤트는_여행을_실패시킨다() {
        service.handleEvent(1L, new AiGenerationEvent("ROUTE_OPTIMIZE_DONE", "{}"));

        verify(job).fail("AI 생성 단계 순서가 올바르지 않습니다.");
        verify(plan).markFailed();
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
        verify(persistenceService, never()).persistIfPresent(any());
    }

    @Test
    void 장소_숙소_경로_순서가_맞으면_일정을_저장하고_완료한다() {
        when(persistenceService.persistIfPresent(job)).thenReturn(true);

        service.handleEvent(1L, new AiGenerationEvent("PLACE_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent("STAY_RECOMMEND_DONE", "{}"));
        service.handleEvent(1L, new AiGenerationEvent(
                "ROUTE_OPTIMIZE_DONE",
                "{\"result\":{\"route_segments\":[]}}"
        ));

        verify(persistenceService).persistIfPresent(job);
        verify(job).complete();
        verify(plan).markCompleted();
        verify(jobRepository).save(job);
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
        assertThat(progressStore.states(1L).values())
                .allMatch(state -> state == com.audigo.domain.travel.entity.TravelGenerationStageState.DONE
                        || state == com.audigo.domain.travel.entity.TravelGenerationStageState.PENDING);
    }
}
