package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.CreateMatchingRequestRequest;
import com.audigo.domain.matching.dto.MatchingRequestResponse;
import com.audigo.domain.matching.entity.MatchingRequest;
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
    private final UserRepository userRepository;

    public MatchingRequestService(
            MatchingRequestRepository matchingRequestRepository,
            UserRepository userRepository
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MatchingRequestResponse create(Long userId, CreateMatchingRequestRequest request) {
        validate(request);
        if (matchingRequestRepository.existsByRequester_Id(userId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        User requester = findActiveUser(userId);
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

    private User findActiveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        return user;
    }
}
