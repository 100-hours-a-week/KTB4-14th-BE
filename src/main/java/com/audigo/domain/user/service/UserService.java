package com.audigo.domain.user.service;

import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.domain.user.dto.MyPageResponse;
import com.audigo.domain.user.dto.UpdateNicknameResponse;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TravelPlanRepository travelPlanRepository;

    public UserService(UserRepository userRepository, TravelPlanRepository travelPlanRepository) {
        this.userRepository = userRepository;
        this.travelPlanRepository = travelPlanRepository;
    }

    @Transactional(readOnly = true)
    public MyPageResponse getMyPage(Long userId) {
        User user = findUser(userId);
        LocalDateTime now = LocalDateTime.now();
        long completedTravelCount = travelPlanRepository.countByUserIdAndStatusAndDepartureDatetimeLessThan(
                userId,
                TravelPlanStatus.COMPLETED,
                now
        );
        long upcomingTravelCount = travelPlanRepository.countByUserIdAndStatusAndDepartureDatetimeGreaterThanEqual(
                userId,
                TravelPlanStatus.COMPLETED,
                now
        );
        return MyPageResponse.from(user, completedTravelCount, upcomingTravelCount);
    }

    @Transactional
    public UpdateNicknameResponse updateNickname(Long userId, String nickname) {
        User user = findUser(userId);
        user.updateNickname(nickname.trim());
        return new UpdateNicknameResponse(user.id(), user.nickname());
    }

    private User findUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        return user;
    }
}
