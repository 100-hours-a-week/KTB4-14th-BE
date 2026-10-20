package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.CreateMatchConnectionRequest;
import com.audigo.domain.matching.dto.MatchConnectionResponse;
import com.audigo.domain.matching.dto.MatchingAiCandidateResponse;
import com.audigo.domain.matching.dto.MatchingAiCandidatesResponse;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidateResponse;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.entity.MatchingProfileTheme;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.matching.repository.MatchingProfileRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingCandidateService {

    private final MatchingRequestRepository matchingRequestRepository;
    private final MatchingProfileRepository matchingProfileRepository;
    private final MatchingAiClient matchingAiClient;
    private final MatchConnectionRepository matchConnectionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MatchingAttemptService matchingAttemptService;

    public MatchingCandidateService(
            MatchingRequestRepository matchingRequestRepository,
            MatchingProfileRepository matchingProfileRepository,
            MatchingAiClient matchingAiClient,
            MatchConnectionRepository matchConnectionRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            MatchingAttemptService matchingAttemptService
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.matchingProfileRepository = matchingProfileRepository;
        this.matchingAiClient = matchingAiClient;
        this.matchConnectionRepository = matchConnectionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.matchingAttemptService = matchingAttemptService;
    }

    public MatchingCandidatesResponse getCandidates(Long userId) {
        return generateCandidates(matchingAttemptService.prepareInitial(userId));
    }

    public MatchingCandidatesResponse rematch(Long userId) {
        return generateCandidates(matchingAttemptService.prepareRematch(userId));
    }

    public MatchingCandidatesResponse retry(Long userId) {
        return generateCandidates(matchingAttemptService.prepareRetry(userId));
    }

    private MatchingCandidatesResponse generateCandidates(
            MatchingAttemptService.AttemptPreparation preparation
    ) {
        try {
            return loadCandidates(preparation);
        } catch (RuntimeException exception) {
            if (!preparation.cached()) {
                matchingAttemptService.fail(preparation.attemptId(), exception);
            }
            throw exception;
        }
    }

    private MatchingCandidatesResponse loadCandidates(MatchingAttemptService.AttemptPreparation preparation) {
        Long userId = preparation.matchingRequest().getRequester().id();
        List<MatchingProfile> candidateProfiles = matchingProfileRepository.findActiveProfilesExceptUser(userId)
                .stream()
                .filter(profile -> !preparation.excludedUserIds().contains(profile.getUserId()))
                .filter(MatchingProfile::isComplete)
                .toList();
        if (preparation.cached()) {
            MatchingAiCandidatesResponse cachedResponse = MatchingAiCandidatesResponse.of(
                    preparation.cachedCandidates().stream()
                            .map(candidate -> new MatchingAiCandidateResponse(
                                    candidate.userId(),
                                    candidate.matchRate()
                            ))
                            .toList()
            );
            return toCandidateResponse(
                    cachedResponse,
                    candidateProfiles,
                    preparation.matchingRequest().getRetryCount(),
                    preparation.matchingRequest().getRemainingRetryCount(),
                    preparation.matchingRequest().canRematch()
            );
        }

        MatchingAiCandidatesResponse aiResponse = matchingAiClient.getCandidates(
                MatchingCandidateAiRequest.from(preparation.matchingRequest(), candidateProfiles)
        );
        List<MatchingCandidateResponse> candidates = mapCandidates(aiResponse, candidateProfiles);
        MatchingAttemptService.AttemptResult result = matchingAttemptService.complete(
                preparation.attemptId(),
                candidates.stream()
                        .map(candidate -> new MatchingAttempt.CandidateScore(
                                candidate.userId(),
                                candidate.matchRate()
                        ))
                        .toList()
        );
        return MatchingCandidatesResponse.of(
                candidates,
                result.retryCount(),
                result.remainingRetryCount(),
                result.canRematch()
        );
    }

    private MatchingCandidatesResponse toCandidateResponse(
            MatchingAiCandidatesResponse aiResponse,
            List<MatchingProfile> candidateProfiles,
            int retryCount,
            int remainingRetryCount,
            boolean canRematch
    ) {
        return MatchingCandidatesResponse.of(
                mapCandidates(aiResponse, candidateProfiles),
                retryCount,
                remainingRetryCount,
                canRematch
        );
    }

    private List<MatchingCandidateResponse> mapCandidates(
            MatchingAiCandidatesResponse aiResponse,
            List<MatchingProfile> candidateProfiles
    ) {
        Map<Long, MatchingProfile> profileByUserId = candidateProfiles.stream()
                .collect(Collectors.toMap(
                        MatchingProfile::getUserId,
                        Function.identity(),
                        (left, right) -> left
                ));
        List<MatchingCandidateResponse> candidates = aiResponse.candidates()
                .stream()
                .map(aiCandidate -> toCandidateResponse(aiCandidate, profileByUserId.get(aiCandidate.userId())))
                .filter(Objects::nonNull)
                .toList();
        return candidates;
    }

    private MatchingCandidateResponse toCandidateResponse(
            MatchingAiCandidateResponse aiCandidate,
            MatchingProfile profile
    ) {
        if (profile == null) {
            return null;
        }
        return new MatchingCandidateResponse(
                profile.getUserId(),
                profile.getNickname(),
                profile.getProfileImageUrl(),
                profile.getThemes().stream().map(MatchingProfileTheme::getTheme).toList(),
                profile.getPace(),
                aiCandidate.matchRate()
        );
    }

    @Transactional
    public MatchConnectionResponse respondCandidate(Long userId, CreateMatchConnectionRequest request) {
        if (request == null || request.targetUserId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        MatchingRequest matchingRequest = matchingRequestRepository
                .findFirstByRequester_IdAndStatusInOrderByIdDesc(
                        userId,
                        List.of(
                                MatchingRequestStatus.ACTIVE,
                                MatchingRequestStatus.EXHAUSTED,
                                MatchingRequestStatus.CONNECTED
                        )
                )
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
                .orElse(null);
        if (connection != null) {
            matchingRequest.markConnected();
            return MatchConnectionResponse.from(connection);
        }

        if (matchingRequest.getStatus() != MatchingRequestStatus.ACTIVE
                && matchingRequest.getStatus() != MatchingRequestStatus.EXHAUSTED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        connection = matchConnectionRepository.save(MatchConnection.create(matchingRequest, targetUser));
        matchingRequest.markConnected();
        notifyParticipants(matchingRequest.getRequester(), targetUser, connection.getId());
        return MatchConnectionResponse.from(connection);
    }

    private void notifyParticipants(User requester, User targetUser, Long matchConnectionId) {
        notificationService.notifyMatchSuccess(
                requester.id(),
                matchConnectionId,
                targetUser.nickname() + "님과 매칭이 완료되었습니다."
        );
        notificationService.notifyMatchSuccess(
                targetUser.id(),
                matchConnectionId,
                requester.nickname() + "님과 매칭이 완료되었습니다."
        );
    }
}
