package com.audigo.domain.travel.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PlaceSearchResponse(
        List<PlaceSearchItemResponse> places,
        int page,
        int size,
        @JsonProperty("is_end") boolean isEnd,
        @JsonProperty("pageable_count") int pageableCount
) {
}
