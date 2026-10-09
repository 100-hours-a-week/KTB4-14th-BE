package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestTheme;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MatchingCandidateAiRequest(
        @JsonProperty("matching_request_id") Long matchingRequestId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("preferred_companion_gender") PreferredCompanionGender preferredCompanionGender,
        @JsonProperty("theme") List<TravelThemeType> themes,
        TravelPaceType pace,
        @JsonProperty("budget_min") Integer budgetMin,
        @JsonProperty("budget_max") Integer budgetMax
) {

    public MatchingCandidateAiRequest {
        themes = themes == null ? List.of() : List.copyOf(themes);
    }

    public static MatchingCandidateAiRequest from(MatchingRequest request) {
        return new MatchingCandidateAiRequest(
                request.getId(),
                request.getRequester().id(),
                request.getPreferredCompanionGender(),
                request.getThemes().stream().map(MatchingRequestTheme::getTheme).toList(),
                request.getPace(),
                request.getBudgetMin(),
                request.getBudgetMax()
        );
    }
}
