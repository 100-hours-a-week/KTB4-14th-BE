package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.Region;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RegionResponse(
        @JsonProperty("region_id")
        Long regionId,
        String name,
        @JsonProperty("full_name")
        String fullName
) {
    public static RegionResponse from(Region region) {
        return new RegionResponse(region.getId(), region.getName(), region.getFullName());
    }
}
