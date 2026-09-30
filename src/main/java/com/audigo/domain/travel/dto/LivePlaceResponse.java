package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record LivePlaceResponse(
        String provider,

        @JsonProperty("provider_place_id")
        String providerPlaceId,

        @JsonProperty("place_name")
        String placeName,

        @JsonProperty("latitude")
        BigDecimal latitude,

        @JsonProperty("longitude")
        BigDecimal longitude,

        @JsonProperty("place_url")
        String placeUrl
) {
}
