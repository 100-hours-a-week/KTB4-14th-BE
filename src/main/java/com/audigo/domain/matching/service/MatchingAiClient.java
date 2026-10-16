package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingAiCandidatesResponse;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;

public interface MatchingAiClient {

    MatchingAiCandidatesResponse getCandidates(MatchingCandidateAiRequest request);
}
