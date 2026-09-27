package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.kakao.transit")
public record KakaoTransitProperties(
        String baseUrl,
        String restApiKey
) {

    public KakaoTransitProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        restApiKey = restApiKey == null ? "" : restApiKey.trim();
    }
}
