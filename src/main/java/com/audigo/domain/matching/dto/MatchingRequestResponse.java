package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.entity.MatchingRequestTheme;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MatchingRequestResponse(
        @JsonProperty("matching_request_id") Long matchingRequestId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("preferred_companion_gender") PreferredCompanionGender preferredCompanionGender,
        @JsonProperty("theme") List<TravelThemeType> themes,
        TravelPaceType pace,
        @JsonProperty("budget_min") Integer budgetMin,
        @JsonProperty("budget_max") Integer budgetMax,
        MatchingRequestStatus status,
        @JsonProperty("latest_attempt_status") MatchingAttemptStatus latestAttemptStatus,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("remaining_retry_count") int remainingRetryCount,
        @JsonProperty("can_rematch") boolean canRematch,
        @JsonProperty("has_result") boolean hasResult,
        @JsonProperty("can_retry") boolean canRetry
) {

    public static MatchingRequestResponse from(MatchingRequest request) {
        return from(request, null, false);
    }

    public static MatchingRequestResponse from(
            MatchingRequest request,
            MatchingAttemptStatus latestAttemptStatus,
            boolean hasResult
    ) {
        return new MatchingRequestResponse(
                request.getId(),
                request.getRequester().id(),
                request.getPreferredCompanionGender(),
                request.getThemes().stream().map(MatchingRequestTheme::getTheme).toList(),
                request.getPace(),
                request.getBudgetMin(),
                request.getBudgetMax(),
                request.getStatus(),
                latestAttemptStatus,
                request.getRetryCount(),
                request.getRemainingRetryCount(),
                request.canRematch(),
                hasResult,
                request.getStatus() == MatchingRequestStatus.ACTIVE
                        && latestAttemptStatus == MatchingAttemptStatus.FAILED
        );
    }
}
