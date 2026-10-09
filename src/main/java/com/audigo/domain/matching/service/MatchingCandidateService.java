package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingCandidateService {

    private final MatchingRequestRepository matchingRequestRepository;
    private final MatchingAiClient matchingAiClient;

    public MatchingCandidateService(
            MatchingRequestRepository matchingRequestRepository,
            MatchingAiClient matchingAiClient
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.matchingAiClient = matchingAiClient;
    }

    @Transactional(readOnly = true)
    public MatchingCandidatesResponse getCandidates(Long userId) {
        MatchingRequest matchingRequest = matchingRequestRepository.findByRequester_Id(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        return matchingAiClient.getCandidates(MatchingCandidateAiRequest.from(matchingRequest));
    }
}
