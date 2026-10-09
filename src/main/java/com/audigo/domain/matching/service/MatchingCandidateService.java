package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.CreateMatchConnectionRequest;
import com.audigo.domain.matching.dto.MatchConnectionResponse;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingCandidateService {

    private final MatchingRequestRepository matchingRequestRepository;
    private final MatchingAiClient matchingAiClient;
    private final MatchConnectionRepository matchConnectionRepository;
    private final UserRepository userRepository;

    public MatchingCandidateService(
            MatchingRequestRepository matchingRequestRepository,
            MatchingAiClient matchingAiClient,
            MatchConnectionRepository matchConnectionRepository,
            UserRepository userRepository
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.matchingAiClient = matchingAiClient;
        this.matchConnectionRepository = matchConnectionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public MatchingCandidatesResponse getCandidates(Long userId) {
        MatchingRequest matchingRequest = matchingRequestRepository.findByRequester_Id(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        return matchingAiClient.getCandidates(MatchingCandidateAiRequest.from(matchingRequest));
    }

    @Transactional
    public MatchConnectionResponse respondCandidate(Long userId, CreateMatchConnectionRequest request) {
        if (request == null || request.targetUserId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        MatchingRequest matchingRequest = matchingRequestRepository.findByRequester_Id(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        User targetUser = userRepository.findById(request.targetUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!targetUser.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        if (Objects.equals(userId, targetUser.id())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        MatchConnection connection = matchConnectionRepository
                .findByMatchingRequest_IdAndTargetUser_IdAndStatus(
                        matchingRequest.getId(),
                        targetUser.id(),
                        MatchConnectionStatus.ACTIVE
                )
                .orElseGet(() -> matchConnectionRepository.save(MatchConnection.create(matchingRequest, targetUser)));
        return MatchConnectionResponse.from(connection);
    }
}
