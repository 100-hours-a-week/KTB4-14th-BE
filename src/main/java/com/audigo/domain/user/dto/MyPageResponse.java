package com.audigo.domain.user.dto;

import com.audigo.domain.user.entity.User;

public record MyPageResponse(
        Long userId,
        String nickname,
        String profileImageUrl,
        String provider,
        String status,
        long completedTravelCount,
        long upcomingTravelCount
) {
    public static MyPageResponse from(User user, long completedTravelCount, long upcomingTravelCount) {
        return new MyPageResponse(
                user.id(),
                user.nickname(),
                user.profileImageUrl(),
                "KAKAO",
                user.status(),
                completedTravelCount,
                upcomingTravelCount
        );
    }
}
