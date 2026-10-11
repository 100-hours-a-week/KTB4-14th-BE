package com.audigo.domain.matching.dto;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MatchingCandidateResponse(
        @JsonProperty("user_id") Long userId,
        String nickname,
        @JsonProperty("profile_image_url") String profileImageUrl,
        @JsonProperty("theme") List<TravelThemeType> themes,
        TravelPaceType pace,
        @JsonProperty("match_rate") Integer matchRate
) {

    public MatchingCandidateResponse {
        themes = themes == null ? List.of() : List.copyOf(themes);
    }
}
