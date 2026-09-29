package com.audigo.domain.travel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LivePlaceResponse(
        String provider,

        @JsonProperty("provider_place_id")
        String providerPlaceId,

        @JsonProperty("place_name")
        String placeName,

        @JsonProperty("place_url")
        String placeUrl
) {
}
