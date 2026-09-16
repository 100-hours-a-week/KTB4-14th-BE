package com.audigo.domain.auth.dto;

import com.audigo.domain.user.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthUserResponse(
        Long userId,
        String nickname,
        String profileImageUrl,
        @JsonProperty("is_new_user")
        boolean isNewUser
) {
    public static AuthUserResponse of(User user, boolean isNewUser) {
        return new AuthUserResponse(user.id(), user.nickname(), user.profileImageUrl(), isNewUser);
    }
}
