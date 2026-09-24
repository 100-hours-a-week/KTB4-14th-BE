package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// AI 서버 SSE 연결 설정

@ConfigurationProperties(prefix = "audigo.ai")
public record AiServerProperties(
        String baseUrl,
        String ssePath,
        String apiToken,
        long connectTimeoutSeconds,
        long readTimeoutSeconds,
        long jobTimeoutSeconds
) {
    public AiServerProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        ssePath = ssePath == null || ssePath.isBlank()
                ? "/api/ai/v1/itinerary-jobs/stream"
                : ssePath.trim();
        apiToken = apiToken == null ? "" : apiToken.trim();
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 5 : connectTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds <= 0 ? 330 : readTimeoutSeconds;
        jobTimeoutSeconds = jobTimeoutSeconds <= 0 ? 300 : jobTimeoutSeconds;
    }
}
