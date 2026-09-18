package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TravelPlanCreatedResponse(
        @JsonProperty("travel_plan_id")
        Long travelPlanId,
        TravelPlanStatus status
) {
    public static TravelPlanCreatedResponse from(TravelPlan travelPlan) {
        return new TravelPlanCreatedResponse(travelPlan.getId(), travelPlan.getStatus());
    }
}
