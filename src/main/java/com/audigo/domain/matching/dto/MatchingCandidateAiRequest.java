package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestTheme;
import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.entity.MatchingProfileTheme;
import com.audigo.domain.matching.entity.MatchingGender;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MatchingCandidateAiRequest(
        RequestPayload request,
        List<CandidateProfilePayload> candidates
) {

    public MatchingCandidateAiRequest {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static MatchingCandidateAiRequest from(MatchingRequest request, List<MatchingProfile> candidateProfiles) {
        return new MatchingCandidateAiRequest(
                RequestPayload.from(request),
                candidateProfiles == null
                        ? List.of()
                        : candidateProfiles.stream().map(CandidateProfilePayload::from).toList()
        );
    }

    public Long matchingRequestId() {
        return request == null ? null : request.matchingRequestId();
    }

    public Long userId() {
        return request == null ? null : request.userId();
    }

    public PreferredCompanionGender preferredCompanionGender() {
        return request == null ? null : request.preferredCompanionGender();
    }

    public List<TravelThemeType> themes() {
        return request == null ? List.of() : request.themes();
    }

    public TravelPaceType pace() {
        return request == null ? null : request.pace();
    }

    public record RequestPayload(
            @JsonProperty("matching_request_id") Long matchingRequestId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("preferred_companion_gender") PreferredCompanionGender preferredCompanionGender,
            @JsonProperty("theme") List<TravelThemeType> themes,
            TravelPaceType pace,
            @JsonProperty("budget_min") Integer budgetMin,
            @JsonProperty("budget_max") Integer budgetMax
    ) {

        public RequestPayload {
            themes = themes == null ? List.of() : List.copyOf(themes);
        }

        static RequestPayload from(MatchingRequest request) {
            return new RequestPayload(
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

    public record CandidateProfilePayload(
            @JsonProperty("user_id") Long userId,
            MatchingGender gender,
            @JsonProperty("theme") List<TravelThemeType> themes,
            TravelPaceType pace
    ) {

        public CandidateProfilePayload {
            themes = themes == null ? List.of() : List.copyOf(themes);
        }

        static CandidateProfilePayload from(MatchingProfile profile) {
            return new CandidateProfilePayload(
                    profile.getUserId(),
                    profile.getGender(),
                    profile.getThemes().stream().map(MatchingProfileTheme::getTheme).toList(),
                    profile.getPace()
            );
        }
    }
}
