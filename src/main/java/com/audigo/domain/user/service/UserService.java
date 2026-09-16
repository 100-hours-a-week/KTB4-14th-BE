package com.audigo.domain.user.service;

import com.audigo.domain.user.dto.MyPageResponse;
import com.audigo.domain.user.dto.UpdateNicknameResponse;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public MyPageResponse getMyPage(Long userId) {
        return MyPageResponse.from(findUser(userId));
    }

    @Transactional
    public UpdateNicknameResponse updateNickname(Long userId, String nickname) {
        User user = findUser(userId);
        user.updateNickname(nickname.trim());
        return new UpdateNicknameResponse(user.id(), user.nickname());
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
