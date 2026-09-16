package com.audigo.domain.user.dto;

public record UpdateNicknameResponse(
        Long userId,
        String nickname
) {
}
