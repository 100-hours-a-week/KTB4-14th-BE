package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record TravelGenerationStatusResponse(
        @JsonProperty("travel_plan_id") Long travelPlanId,
        @JsonProperty("generation_job_id") Long generationJobId,
        TravelPlanStatus status,
        List<GenerationStepResponse> steps,
        @JsonProperty("error_message") String errorMessage
) {
    public static TravelGenerationStatusResponse from(
            TravelGenerationJob job,
            Map<TravelGenerationStage, TravelGenerationStageState> states
    ) {
        return new TravelGenerationStatusResponse(
                job.getTravelPlan().getId(),
                job.getId(),
                job.getStatus(),
                List.of(
                        GenerationStepResponse.of(TravelGenerationStage.PLACE_RECOMMEND,
                                stateOf(states, TravelGenerationStage.PLACE_RECOMMEND, job.getStatus())),
                        GenerationStepResponse.of(TravelGenerationStage.STAY_RECOMMEND,
                                stateOf(states, TravelGenerationStage.STAY_RECOMMEND, job.getStatus())),
                        GenerationStepResponse.of(TravelGenerationStage.ROUTE_OPTIMIZE,
                                stateOf(states, TravelGenerationStage.ROUTE_OPTIMIZE, job.getStatus()))
                ),
                job.getErrorMessage()
        );
    }

    private static TravelGenerationStageState stateOf(
            Map<TravelGenerationStage, TravelGenerationStageState> states,
            TravelGenerationStage stage,
            TravelPlanStatus status
    ) {
        TravelGenerationStageState state = states == null ? null : states.get(stage);
        if (state != null) {
            return state;
        }
        if (status == TravelPlanStatus.COMPLETED) {
            return TravelGenerationStageState.DONE;
        }
        if (status == TravelPlanStatus.FAILED) {
            return TravelGenerationStageState.FAILED;
        }
        return TravelGenerationStageState.PENDING;
    }
}
