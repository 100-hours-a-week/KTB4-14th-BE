package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.PlaceProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record PlaceSearchItemResponse(
        PlaceProvider provider,

        @JsonProperty("provider_place_id")
        String providerPlaceId,

        @JsonProperty("place_name")
        String placeName,

        String address,

        @JsonProperty("road_address")
        String roadAddress,

        BigDecimal latitude,

        BigDecimal longitude
) {
}
