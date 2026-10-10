package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchingGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateMatchingProfileRequest(
        @JsonProperty("is_active")
        @JsonAlias("isActive")
        @NotNull(message = "매칭 활성화 여부는 필수입니다.")
        Boolean active,

        @NotNull(message = "성별은 필수입니다.")
        MatchingGender gender,

        @NotNull(message = "여행 속도는 필수입니다.")
        TravelPaceType pace,

        @NotNull(message = "선호 테마 목록은 필수입니다.")
        @Size(max = 4, message = "선호 테마는 최대 4개까지 선택할 수 있습니다.")
        List<@NotNull(message = "선호 테마 값은 필수입니다.") TravelThemeType> themes
) {

    public UpdateMatchingProfileRequest {
        themes = themes == null ? null : List.copyOf(themes);
    }

    @AssertTrue(message = "매칭 대상으로 설정하려면 선호 테마를 1개 이상 선택해야 합니다.")
    public boolean isActiveProfileComplete() {
        return active == null || !active || (themes != null && !themes.isEmpty());
    }
}
