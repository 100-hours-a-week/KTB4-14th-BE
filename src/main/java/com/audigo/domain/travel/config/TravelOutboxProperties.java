package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.sqs.outbox")
public record TravelOutboxProperties(
        int batchSize,
        long retryBaseDelaySeconds,
        long retryMaxDelaySeconds,
        long processingLeaseSeconds
) {

    public TravelOutboxProperties {
        requirePositive(batchSize, "Outbox 발행 배치 크기");
        requirePositive(retryBaseDelaySeconds, "Outbox 기본 재시도 간격");
        requirePositive(retryMaxDelaySeconds, "Outbox 최대 재시도 간격");
        requirePositive(processingLeaseSeconds, "Outbox 처리 임대 시간");
        if (retryMaxDelaySeconds < retryBaseDelaySeconds) {
            throw new IllegalArgumentException("Outbox 최대 재시도 간격은 기본 간격보다 짧을 수 없습니다.");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "은 0보다 커야 합니다.");
        }
    }
}
