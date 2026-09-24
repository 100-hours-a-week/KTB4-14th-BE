package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// SSE 단계 진행 상태와 일정 저장에 사용할 원문 결과를 보관
// 서버가 재시작되면 초기화되며, 전체 작업 상태는 DB에서 복구
@Component
public class TravelGenerationProgressStore {

    private final Map<Long, Progress> progressByJob = new ConcurrentHashMap<>();

    public void initialize(Long jobId) {
        progressByJob.put(jobId, new Progress());
    }

    public void update(Long jobId, TravelGenerationStage stage, TravelGenerationStageState state, String payload) {
        progressByJob.computeIfAbsent(jobId, ignored -> new Progress())
                .update(stage, state, payload);
    }

    public Map<TravelGenerationStage, TravelGenerationStageState> states(Long jobId) {
        Progress progress = progressByJob.get(jobId);
        return progress == null ? Map.of() : progress.states();
    }

    public String payload(Long jobId, TravelGenerationStage stage) {
        Progress progress = progressByJob.get(jobId);
        return progress == null ? null : progress.payload(stage);
    }

    private static final class Progress {
        private final Map<TravelGenerationStage, TravelGenerationStageState> states =
                new EnumMap<>(TravelGenerationStage.class);
        private final Map<TravelGenerationStage, String> payloads =
                new EnumMap<>(TravelGenerationStage.class);

        private Progress() {
            for (TravelGenerationStage stage : TravelGenerationStage.values()) {
                states.put(stage, TravelGenerationStageState.PENDING);
            }
        }

        private synchronized void update(TravelGenerationStage stage, TravelGenerationStageState state, String payload) {
            states.put(stage, state);
            if (payload != null && !payload.isBlank()) {
                payloads.put(stage, payload);
            }
        }

        private synchronized Map<TravelGenerationStage, TravelGenerationStageState> states() {
            return Collections.unmodifiableMap(new EnumMap<>(states));
        }

        private synchronized String payload(TravelGenerationStage stage) {
            return payloads.get(stage);
        }
    }
}
