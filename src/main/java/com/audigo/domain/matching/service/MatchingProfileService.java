package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingProfileResponse;
import com.audigo.domain.matching.dto.UpdateMatchingProfileRequest;
import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.repository.MatchingProfileRepository;
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
public class MatchingProfileService {

    private final MatchingProfileRepository matchingProfileRepository;
    private final UserRepository userRepository;

    public MatchingProfileService(
            MatchingProfileRepository matchingProfileRepository,
            UserRepository userRepository
    ) {
        this.matchingProfileRepository = matchingProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public MatchingProfileResponse getMyProfile(Long userId) {
        return matchingProfileRepository.findByUser_Id(userId)
                .map(MatchingProfileResponse::from)
                .orElseGet(MatchingProfileResponse::empty);
    }

    @Transactional
    public MatchingProfileResponse updateMyProfile(Long userId, UpdateMatchingProfileRequest request) {
        validate(request);
        User user = findActiveUser(userId);
        MatchingProfile profile = matchingProfileRepository.findByUser_Id(userId)
                .orElseGet(() -> MatchingProfile.create(user, request.active(), request.pace(), request.themes()));
        profile.update(request.active(), request.pace(), request.themes());
        return MatchingProfileResponse.from(matchingProfileRepository.save(profile));
    }

    private void validate(UpdateMatchingProfileRequest request) {
        if (request == null || request.active() == null || request.pace() == null || request.themes() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<TravelThemeType> themes = request.themes();
        if (themes.size() > MatchingProfile.MAX_THEME_COUNT || new HashSet<>(themes).size() != themes.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.active() && themes.isEmpty()) {
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
