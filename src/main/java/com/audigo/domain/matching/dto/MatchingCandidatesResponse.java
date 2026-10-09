package com.audigo.domain.matching.dto;

import java.util.List;

public record MatchingCandidatesResponse(
        List<MatchingCandidateResponse> candidates,
        int count
) {

    public MatchingCandidatesResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        count = candidates.size();
    }

    public static MatchingCandidatesResponse empty() {
        return new MatchingCandidatesResponse(List.of(), 0);
    }

    public static MatchingCandidatesResponse of(List<MatchingCandidateResponse> candidates) {
        return new MatchingCandidatesResponse(candidates, candidates == null ? 0 : candidates.size());
    }
}
