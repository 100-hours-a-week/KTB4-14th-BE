package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.sqs.result-consumer")
public record TravelResultQueueProperties(
        int waitTimeSeconds,
        int maxMessages,
        int concurrency,
        int executorQueueCapacity,
        int maxReceiveCount
) {

    public TravelResultQueueProperties {
        requireRange(waitTimeSeconds, 1, 20, "Result Queue Long Polling 시간");
        requireRange(maxMessages, 1, 10, "Result Queue 배치 크기");
        requireRange(concurrency, 1, 20, "Result Queue 처리 동시성");
        requireRange(executorQueueCapacity, 1, 1000, "Result Queue 실행 대기열 크기");
        requireRange(maxReceiveCount, 1, 100, "Result Queue 최대 수신 횟수");
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + "은 " + min + " 이상 " + max + " 이하여야 합니다.");
        }
    }
}
