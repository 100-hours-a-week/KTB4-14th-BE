package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RouteRecalculationResponse(
        @JsonProperty("travel_plan_id") Long travelPlanId,
        List<ItineraryResponse.RouteSegmentResponse> routes
) {
    public RouteRecalculationResponse {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
