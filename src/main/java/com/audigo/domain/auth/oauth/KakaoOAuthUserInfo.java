package com.audigo.domain.auth.oauth;

public record KakaoOAuthUserInfo(
        String providerUserId,
        String nickname,
        String profileImageUrl
) {
}
