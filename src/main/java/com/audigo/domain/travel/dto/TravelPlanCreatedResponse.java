package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TravelPlanCreatedResponse(
        @JsonProperty("travel_plan_id")
        Long travelPlanId,
        @JsonProperty("generation_job_id")
        Long generationJobId,
        TravelPlanStatus status
) {
    public static TravelPlanCreatedResponse from(TravelPlan travelPlan) {
        return new TravelPlanCreatedResponse(travelPlan.getId(), null, travelPlan.getStatus());
    }

    public static TravelPlanCreatedResponse from(TravelPlan travelPlan, Long generationJobId) {
        return new TravelPlanCreatedResponse(travelPlan.getId(), generationJobId, travelPlan.getStatus());
    }
}
