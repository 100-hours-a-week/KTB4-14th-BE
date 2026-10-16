package com.audigo.global.migration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// DB 컷오버 제어 설정
@ConfigurationProperties(prefix = "audigo.migration")
public record MigrationProperties(
        boolean enabled,
        String instanceId,
        String adminKey,
        Duration holdTimeout,
        int pendingCapacity,
        int resumeMaxConcurrency,
        Duration drainTimeout,
        int errorHistorySize
) {
    public MigrationProperties {
        instanceId = instanceId == null || instanceId.isBlank() ? "local" : instanceId.trim();
        adminKey = adminKey == null ? "" : adminKey.trim();
        holdTimeout = positiveOr(holdTimeout, Duration.ofSeconds(50));
        pendingCapacity = pendingCapacity <= 0 ? 1000 : pendingCapacity;
        resumeMaxConcurrency = resumeMaxConcurrency <= 0 ? 4 : resumeMaxConcurrency;
        drainTimeout = positiveOr(drainTimeout, Duration.ofSeconds(360));
        errorHistorySize = errorHistorySize <= 0 ? 100 : errorHistorySize;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
