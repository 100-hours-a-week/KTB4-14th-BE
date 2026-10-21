package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// AI 서버에 전달하고 재시도 시 복원할 수 있도록 작업별 스냅샷으로 저장하는 내부 요청 DTO
public record AiTravelGenerationRequest(
        @JsonProperty("travel_plan_id") Long travelPlanId,
        @JsonProperty("region_id") Long regionId,
        @JsonProperty("region_name") String regionName,
        @JsonProperty("arrival_datetime") LocalDateTime arrivalDatetime,
        @JsonProperty("departure_datetime") LocalDateTime departureDatetime,
        Integer headcount,
        @JsonProperty("companion_type") String companionType,
        Preference preference,
        @JsonProperty("required_places") List<PlaceContext> requiredPlaces
) {
    public AiTravelGenerationRequest {
        requiredPlaces = requiredPlaces == null ? List.of() : List.copyOf(requiredPlaces);
    }

    public record Preference(
            @JsonProperty("pace_type") TravelPaceType paceType,
            @JsonProperty("transport_type") TravelTransportType transportType,
            @JsonProperty("budget_min") Integer budgetMin,
            @JsonProperty("budget_max") Integer budgetMax,
            @JsonProperty("budget_type") String budgetType,
            @JsonProperty("distance_preference") Integer distancePreference,
            List<String> themes,
            List<String> foods,
            @JsonProperty("extra_request") String extraRequest
    ) {
    }

    public record PlaceContext(
            String provider,
            @JsonProperty("provider_place_id") String providerPlaceId,
            @JsonProperty("place_name") String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            @JsonProperty("place_type")
            @JsonAlias("category")
            String placeType,
            Integer order
    ) {
    }
}
