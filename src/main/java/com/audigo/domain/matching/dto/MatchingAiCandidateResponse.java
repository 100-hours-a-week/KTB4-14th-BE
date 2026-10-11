package com.audigo.domain.matching.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MatchingAiCandidateResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("match_rate") Integer matchRate
) {
}
