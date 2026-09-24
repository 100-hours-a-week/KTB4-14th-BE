package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.BudgetType;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TravelPreferenceRequest(
        @JsonProperty("pace_type")
        @NotNull(message = "여행 속도는 필수입니다.")
        TravelPaceType paceType,

        @JsonProperty("transport_type")
        @NotNull(message = "이동 수단은 필수입니다.")
        TravelTransportType transportType,

        @JsonProperty("budget_min")
        @NotNull(message = "최소 예산은 필수입니다.")
        @Min(value = 0, message = "최소 예산은 0원 이상이어야 합니다.")
        @Max(value = 3_000_000, message = "최소 예산은 3,000,000원 이하여야 합니다.")
        Integer budgetMin,

        @JsonProperty("budget_max")
        @NotNull(message = "최대 예산은 필수입니다.")
        @Min(value = 0, message = "최대 예산은 0원 이상이어야 합니다.")
        @Max(value = 3_000_000, message = "최대 예산은 3,000,000원 이하여야 합니다.")
        Integer budgetMax,

        @JsonProperty("budget_type")
        @NotNull(message = "예산 통화는 필수입니다.")
        BudgetType budgetType,

        @JsonProperty("distance_preference")
        @NotNull(message = "거리 선호도는 필수입니다.")
        @Min(value = 0, message = "거리 선호도는 0 이상이어야 합니다.")
        @Max(value = 100, message = "거리 선호도는 100 이하여야 합니다.")
        Integer distancePreference,

        @NotNull(message = "테마 목록은 필수입니다.")
        @Size(max = 3, message = "테마는 최대 3개까지 선택할 수 있습니다.")
        List<@NotNull(message = "테마 값은 필수입니다.") TravelThemeType> themes,

        @NotNull(message = "음식 목록은 필수입니다.")
        List<@NotNull(message = "음식 값은 필수입니다.") FoodType> foods,

        @JsonProperty("extra_request")
        @Size(max = 300, message = "추가 요청은 300자 이하여야 합니다.")
        String extraRequest
) {
    public TravelPreferenceRequest {
        themes = themes == null ? null : List.copyOf(themes);
        foods = foods == null ? List.of() : List.copyOf(foods);
        extraRequest = extraRequest == null ? "" : extraRequest.trim();
    }

    @AssertTrue(message = "최소 예산은 최대 예산보다 작거나 같아야 합니다.")
    public boolean isBudgetRangeValid() {
        if (budgetMin == null || budgetMax == null) {
            return true;
        }
        return budgetMin <= budgetMax;
    }
}
