package com.audigo.domain.matching.dto;

import java.util.List;

public record MatchingAiCandidatesResponse(
        List<MatchingAiCandidateResponse> candidates,
        int count
) {

    public MatchingAiCandidatesResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        count = candidates.size();
    }

    public static MatchingAiCandidatesResponse empty() {
        return new MatchingAiCandidatesResponse(List.of(), 0);
    }

    public static MatchingAiCandidatesResponse of(List<MatchingAiCandidateResponse> candidates) {
        return new MatchingAiCandidatesResponse(candidates, candidates == null ? 0 : candidates.size());
    }
}
