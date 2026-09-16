package com.audigo.domain.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.oauth.kakao")
public record KakaoOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {
}
