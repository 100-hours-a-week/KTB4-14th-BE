package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationProgress;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.domain.travel.repository.TravelGenerationProgressRepository;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelGenerationProgressStoreTest {

    private final Map<TravelGenerationStage, TravelGenerationProgress> persisted =
            new EnumMap<>(TravelGenerationStage.class);
    private TravelGenerationProgressRepository progressRepository;
    private TravelGenerationJobRepository jobRepository;
    private TravelGenerationProgressStore store;

    @BeforeEach
    void setUp() {
        progressRepository = mock(TravelGenerationProgressRepository.class);
        jobRepository = mock(TravelGenerationJobRepository.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(progressRepository.findAllByJobId(1L))
                .thenAnswer(ignored -> persisted.values().stream().toList());
        when(progressRepository.findAllStatesByJobId(1L))
                .thenAnswer(ignored -> persisted.values().stream()
                        .map(progress -> new TravelGenerationProgressRepository.StageStateView() {
                            @Override
                            public TravelGenerationStage getStage() {
                                return progress.getStage();
                            }

                            @Override
                            public TravelGenerationStageState getState() {
                                return progress.getState();
                            }
                        })
                        .toList());
        when(progressRepository.findByJobIdAndStage(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get(invocation.getArgument(1))));
        when(progressRepository.save(any())).thenAnswer(invocation -> {
            TravelGenerationProgress progress = invocation.getArgument(0);
            persisted.put(progress.getStage(), progress);
            return progress;
        });
        when(progressRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<TravelGenerationProgress> values = invocation.getArgument(0);
            values.forEach(progress -> persisted.put(progress.getStage(), progress));
            return values;
        });
        store = new TravelGenerationProgressStore(progressRepository, jobRepository);
    }

    @Test
    void 초기화한_단계와_payload는_새로운_저장소_조회에서도_복원된다() {
        store.initialize(1L);
        store.update(1L, TravelGenerationStage.ROUTE_OPTIMIZE,
                TravelGenerationStageState.DONE, "{\"days\":[]}");

        TravelGenerationProgressStore restoredStore =
                new TravelGenerationProgressStore(progressRepository, jobRepository);

        assertThat(restoredStore.states(1L)).hasSize(TravelGenerationStage.values().length);
        assertThat(restoredStore.states(1L).get(TravelGenerationStage.ROUTE_OPTIMIZE))
                .isEqualTo(TravelGenerationStageState.DONE);
        assertThat(restoredStore.payload(1L, TravelGenerationStage.ROUTE_OPTIMIZE))
                .isEqualTo("{\"days\":[]}");
    }
}
