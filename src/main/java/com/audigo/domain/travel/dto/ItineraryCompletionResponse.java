package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ItineraryCompletionResponse(
        @JsonProperty("itinerary_item_id") Long itineraryItemId,
        @JsonProperty("is_completed") boolean completed,
        @JsonProperty("completed_at") LocalDateTime completedAt
) {
}
