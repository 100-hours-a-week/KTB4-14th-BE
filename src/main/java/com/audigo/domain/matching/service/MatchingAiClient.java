package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;

public interface MatchingAiClient {

    MatchingCandidatesResponse getCandidates(MatchingCandidateAiRequest request);
}
