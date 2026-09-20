package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// AI 서버 SSE 연결 설정

@ConfigurationProperties(prefix = "audigo.ai")
public record AiServerProperties(
        String baseUrl,
        String ssePath
) {
    public AiServerProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        ssePath = ssePath == null || ssePath.isBlank()
                ? "/api/ai/v1/itinerary-jobs/stream"
                : ssePath.trim();
    }
}
