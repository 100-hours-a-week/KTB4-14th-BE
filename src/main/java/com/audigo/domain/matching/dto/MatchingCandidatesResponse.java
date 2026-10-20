package com.audigo.domain.matching.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MatchingCandidatesResponse(
        List<MatchingCandidateResponse> candidates,
        int count,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("remaining_retry_count") int remainingRetryCount,
        @JsonProperty("can_rematch") boolean canRematch
) {

    public MatchingCandidatesResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        count = candidates.size();
    }

    public static MatchingCandidatesResponse empty() {
        return new MatchingCandidatesResponse(List.of(), 0, 0, 3, true);
    }

    public static MatchingCandidatesResponse of(List<MatchingCandidateResponse> candidates) {
        return of(candidates, 0, 3, true);
    }

    public static MatchingCandidatesResponse of(
            List<MatchingCandidateResponse> candidates,
            int retryCount,
            int remainingRetryCount,
            boolean canRematch
    ) {
        return new MatchingCandidatesResponse(
                candidates,
                candidates == null ? 0 : candidates.size(),
                retryCount,
                remainingRetryCount,
                canRematch
        );
    }
}
