package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record BusArrivalLookupResponse(
        @JsonProperty("route_segment_id") Long routeSegmentId,
        @JsonProperty("available") boolean available,
        @JsonProperty("arrivals") List<BusArrivalResponse> arrivals,
        @JsonProperty("last_refreshed_at") LocalDateTime lastRefreshedAt
) {

    public BusArrivalLookupResponse {
        arrivals = arrivals == null ? List.of() : List.copyOf(arrivals);
    }

    public static BusArrivalLookupResponse unavailable(Long routeSegmentId) {
        return new BusArrivalLookupResponse(routeSegmentId, false, List.of(), null);
    }
}
