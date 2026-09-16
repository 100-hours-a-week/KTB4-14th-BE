package com.audigo.domain.user.dto;

import com.audigo.domain.user.entity.User;

public record MyPageResponse(
        String nickname,
        String profileImage,
        String provider,
        long completedTravelCount,
        long upcomingTravelCount
) {
    public static MyPageResponse from(User user) {
        return new MyPageResponse(user.nickname(), user.profileImageUrl(), "KAKAO", 0, 0);
    }
}
