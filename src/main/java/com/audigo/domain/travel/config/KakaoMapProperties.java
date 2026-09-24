package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.kakao.map")
public record KakaoMapProperties(
        String restApiKey
) {
}