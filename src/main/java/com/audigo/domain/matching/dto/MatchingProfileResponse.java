package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.entity.MatchingProfileTheme;
import com.audigo.domain.matching.entity.MatchingGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MatchingProfileResponse(
        boolean exists,
        @JsonProperty("is_active") boolean active,
        MatchingGender gender,
        TravelPaceType pace,
        List<TravelThemeType> themes,
        @JsonProperty("is_complete") boolean complete,
        @JsonProperty("can_match") boolean canMatch
) {

    public static MatchingProfileResponse empty() {
        return new MatchingProfileResponse(false, false, null, null, List.of(), false, false);
    }

    public static MatchingProfileResponse from(MatchingProfile profile) {
        List<TravelThemeType> themes = profile.getThemes()
                .stream()
                .map(MatchingProfileTheme::getTheme)
                .toList();
        return new MatchingProfileResponse(
                true,
                profile.isActive(),
                profile.getGender(),
                profile.getPace(),
                themes,
                profile.isComplete(),
                profile.canMatch()
        );
    }
}
