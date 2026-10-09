package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMatchingRequestRequest(
        @JsonProperty("preferred_companion_gender")
        @NotNull(message = "선호 동행자 성별은 필수입니다.")
        PreferredCompanionGender preferredCompanionGender,

        @JsonProperty("theme")
        @JsonAlias("themes")
        @NotNull(message = "선호 테마 목록은 필수입니다.")
        @Size(min = 1, max = 3, message = "선호 테마는 1개 이상 3개 이하로 선택해야 합니다.")
        List<@NotNull(message = "선호 테마 값은 필수입니다.") TravelThemeType> themes,

        @NotNull(message = "여행 속도는 필수입니다.")
        TravelPaceType pace,

        @JsonProperty("budget_min")
        @Min(value = 0, message = "최소 예산은 0원 이상이어야 합니다.")
        @Max(value = 3_000_000, message = "최소 예산은 3,000,000원 이하여야 합니다.")
        Integer budgetMin,

        @JsonProperty("budget_max")
        @Min(value = 0, message = "최대 예산은 0원 이상이어야 합니다.")
        @Max(value = 3_000_000, message = "최대 예산은 3,000,000원 이하여야 합니다.")
        Integer budgetMax
) {

    public CreateMatchingRequestRequest {
        themes = themes == null ? null : List.copyOf(themes);
    }

    @AssertTrue(message = "최소 예산과 최대 예산은 함께 입력해야 합니다.")
    public boolean isBudgetPresenceValid() {
        return (budgetMin == null && budgetMax == null) || (budgetMin != null && budgetMax != null);
    }

    @AssertTrue(message = "최소 예산은 최대 예산보다 작거나 같아야 합니다.")
    public boolean isBudgetRangeValid() {
        return budgetMin == null || budgetMax == null || budgetMin <= budgetMax;
    }
}
