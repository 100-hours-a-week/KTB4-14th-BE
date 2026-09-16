package com.audigo.domain.auth.oauth;

public interface KakaoOAuthClient {

    KakaoOAuthUserInfo fetchUserInfo(String authorizationCode);
}
