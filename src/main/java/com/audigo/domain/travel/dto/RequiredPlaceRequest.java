package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.PlaceType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequiredPlaceRequest(
        @NotNull(message = "장소 제공자는 필수입니다.")
        PlaceProvider provider,

        @JsonProperty("provider_place_id")
        @NotBlank(message = "카카오 장소 ID는 필수입니다.")
        String providerPlaceId,

        @JsonProperty("place_name")
        @NotBlank(message = "장소명은 필수입니다.")
        String placeName,

        @NotBlank(message = "장소 주소는 필수입니다.")
        String address,

        @NotNull(message = "위도는 필수입니다.")
        @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
        BigDecimal latitude,

        @NotNull(message = "경도는 필수입니다.")
        @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
        BigDecimal longitude,

        @JsonProperty("place_type")
        @JsonAlias("category")
        PlaceType placeType,

        @Min(value = 1, message = "장소 순서는 1 이상이어야 합니다.")
        Integer order
) {
    public RequiredPlaceRequest {
        placeType = placeType == null ? PlaceType.TOURISM : placeType;
        providerPlaceId = providerPlaceId == null ? null : providerPlaceId.trim();
        placeName = placeName == null ? null : placeName.trim();
        address = address == null ? null : address.trim();
    }

    public int resolvedOrder(int index) {
        return order == null ? index + 1 : order;
    }
}
