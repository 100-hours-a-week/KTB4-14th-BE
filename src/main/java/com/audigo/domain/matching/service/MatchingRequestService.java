package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.CreateMatchingRequestRequest;
import com.audigo.domain.matching.dto.MatchingRequestResponse;
import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.repository.MatchingAttemptRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingRequestService {

    private final MatchingRequestRepository matchingRequestRepository;
    private final MatchingAttemptRepository matchingAttemptRepository;
    private final UserRepository userRepository;

    public MatchingRequestService(
            MatchingRequestRepository matchingRequestRepository,
            MatchingAttemptRepository matchingAttemptRepository,
            UserRepository userRepository
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.matchingAttemptRepository = matchingAttemptRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MatchingRequestResponse create(Long userId, CreateMatchingRequestRequest request) {
        validate(request);
        User requester = findActiveUserForUpdate(userId);
        if (matchingRequestRepository.existsByRequester_IdAndStatus(userId, MatchingRequestStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_ALREADY_ACTIVE);
        }
        MatchingRequest matchingRequest = MatchingRequest.create(
                requester,
                request.preferredCompanionGender(),
                request.pace(),
                request.budgetMin(),
                request.budgetMax(),
                request.themes()
        );
        return MatchingRequestResponse.from(matchingRequestRepository.save(matchingRequest));
    }

    @Transactional(readOnly = true)
    public MatchingRequestResponse getMine(Long userId) {
        MatchingRequest request = findMine(userId);
        MatchingAttemptStatus latestAttemptStatus = matchingAttemptRepository
                .findFirstByMatchingRequest_IdOrderByIdDesc(request.getId())
                .map(attempt -> attempt.getStatus())
                .orElse(null);
        boolean hasResult = matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(
                request.getId(),
                MatchingAttemptStatus.SUCCEEDED
        );
        return MatchingRequestResponse.from(request, latestAttemptStatus, hasResult);
    }

    @Transactional
    public void cancelMine(Long userId) {
        MatchingRequest request = matchingRequestRepository.findLatestByRequesterIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        if (request.getStatus() != MatchingRequestStatus.ACTIVE
                && request.getStatus() != MatchingRequestStatus.EXHAUSTED) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        request.cancel();
        matchingAttemptRepository.findAllByMatchingRequest_IdAndStatus(
                request.getId(), MatchingAttemptStatus.PROCESSING
        ).forEach(attempt -> attempt.fail("matching_request_cancelled"));
    }

    private MatchingRequest findMine(Long userId) {
        MatchingRequest request = matchingRequestRepository.findFirstByRequester_IdOrderByIdDesc(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        if (request.getStatus() != MatchingRequestStatus.ACTIVE
                && request.getStatus() != MatchingRequestStatus.EXHAUSTED) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        return request;
    }

    private void validate(CreateMatchingRequestRequest request) {
        if (request == null
                || request.preferredCompanionGender() == null
                || request.pace() == null
                || request.themes() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<TravelThemeType> themes = request.themes();
        if (themes.isEmpty()
                || themes.size() > MatchingRequest.MAX_THEME_COUNT
                || new HashSet<>(themes).size() != themes.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Integer budgetMin = request.budgetMin();
        Integer budgetMax = request.budgetMax();
        if ((budgetMin == null) != (budgetMax == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (budgetMin != null && (budgetMin < 0 || budgetMax > 3_000_000 || budgetMin > budgetMax)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private User findActiveUserForUpdate(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        return user;
    }
}
