package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.sqs")
public record TravelSqsProperties(
        boolean enabled,
        String region,
        String jobQueueUrl,
        String resultQueueUrl,
        String endpoint
) {

    public TravelSqsProperties {
        region = normalize(region);
        jobQueueUrl = normalize(jobQueueUrl);
        resultQueueUrl = normalize(resultQueueUrl);
        endpoint = normalize(endpoint);

        if (enabled) {
            requireConfigured(region, "AWS Region");
            requireConfigured(jobQueueUrl, "AI Job Queue URL");
            requireConfigured(resultQueueUrl, "AI Result Queue URL");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireConfigured(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 설정이 필요합니다.");
        }
    }
}
