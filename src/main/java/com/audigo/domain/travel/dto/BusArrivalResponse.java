package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record BusArrivalResponse(
        @JsonProperty("leg_sequence") int legSequence,
        @JsonProperty("route_number") String routeNumber,
        @JsonProperty("next_arrival_minutes") Integer nextArrivalMinutes,
        @JsonProperty("expected_arrival_at") LocalDateTime expectedArrivalAt,
        @JsonProperty("remaining_stops") Integer remainingStops,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") LocalDateTime fetchedAt
) {
}
