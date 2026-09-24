package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.CompanionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelPlanRequest(
        @JsonProperty("region_id")
        @NotNull(message = "여행 지역은 필수입니다.")
        Long regionId,

        @JsonProperty("arrival_datetime")
        @NotNull(message = "도착 일시는 필수입니다.")
        LocalDateTime arrivalDatetime,

        @JsonProperty("departure_datetime")
        @NotNull(message = "출발 일시는 필수입니다.")
        LocalDateTime departureDatetime,

        @Min(value = 1, message = "인원은 1명 이상이어야 합니다.")
        @Max(value = 30, message = "인원은 30명 이하여야 합니다.")
        @NotNull(message = "인원은 필수입니다.")
        Integer headcount,

        @JsonProperty("companion_type")
        @NotNull(message = "동행 유형은 필수입니다.")
        CompanionType companionType,

        @NotNull(message = "여행 취향은 필수입니다.")
        @Valid
        TravelPreferenceRequest preference,

        @JsonProperty("required_places")
        @Valid
        List<@NotNull(message = "필수 장소 항목은 비어 있을 수 없습니다.") RequiredPlaceRequest> requiredPlaces
) {
    public TravelPlanRequest {
        requiredPlaces = requiredPlaces == null ? List.of() : List.copyOf(requiredPlaces);
    }

    @AssertTrue(message = "여행지 출발 시간은 도착 시간보다 늦어야 합니다.")
    public boolean isTravelPeriodValid() {
        return arrivalDatetime == null
                || departureDatetime == null
                || arrivalDatetime.isBefore(departureDatetime);
    }

    @AssertTrue(message = "혼자 여행은 1명, 동행 여행은 2명 이상이어야 합니다.")
    public boolean isHeadcountCompatibleWithCompanion() {
        if (companionType == null || headcount == null) {
            return true;
        }
        return companionType == CompanionType.SOLO ? headcount == 1 : headcount >= 2;
    }
}
