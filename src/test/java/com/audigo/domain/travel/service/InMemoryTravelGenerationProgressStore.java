package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryTravelGenerationProgressStore extends TravelGenerationProgressStore {

    private final Map<Long, Map<TravelGenerationStage, TravelGenerationStageState>> states =
            new ConcurrentHashMap<>();
    private final Map<Long, Map<TravelGenerationStage, String>> payloads = new ConcurrentHashMap<>();

    InMemoryTravelGenerationProgressStore() {
        super(null, null);
    }

    @Override
    public void initialize(Long jobId) {
        states.computeIfAbsent(jobId, ignored -> {
            Map<TravelGenerationStage, TravelGenerationStageState> initialized =
                    new EnumMap<>(TravelGenerationStage.class);
            for (TravelGenerationStage stage : TravelGenerationStage.values()) {
                initialized.put(stage, TravelGenerationStageState.PENDING);
            }
            return initialized;
        });
    }

    @Override
    public void update(Long jobId, TravelGenerationStage stage, TravelGenerationStageState state, String payload) {
        initialize(jobId);
        states.get(jobId).put(stage, state);
        if (payload != null && !payload.isBlank()) {
            payloads.computeIfAbsent(jobId, ignored -> new EnumMap<>(TravelGenerationStage.class))
                    .put(stage, payload);
        }
    }

    @Override
    public Map<TravelGenerationStage, TravelGenerationStageState> states(Long jobId) {
        Map<TravelGenerationStage, TravelGenerationStageState> values = states.get(jobId);
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(values));
    }

    @Override
    public String payload(Long jobId, TravelGenerationStage stage) {
        return payloads.getOrDefault(jobId, Map.of()).get(stage);
    }
}
