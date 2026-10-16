package com.audigo.domain.matching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.ai.matching")
public record MatchingAiProperties(
        String candidatesPath,
        boolean mockEnabled,
        int candidateLimit
) {

    public MatchingAiProperties {
        candidatesPath = candidatesPath == null || candidatesPath.isBlank()
                ? "/api/ai/v1/matches/candidates"
                : candidatesPath.trim();
        candidateLimit = candidateLimit <= 0 ? 3 : candidateLimit;
    }
}
