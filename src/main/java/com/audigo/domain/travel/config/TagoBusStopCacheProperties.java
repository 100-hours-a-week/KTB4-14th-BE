package com.audigo.domain.travel.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.transit-stop.cache")
public record TagoBusStopCacheProperties(
        Boolean enabled,
        Duration cityTtl,
        Duration stopTtl,
        Duration emptyStopTtl,
        Double ttlJitterRatio
) {
    public TagoBusStopCacheProperties {
        enabled = enabled == null || enabled;
        cityTtl = positiveOrDefault(cityTtl, Duration.ofHours(24));
        stopTtl = positiveOrDefault(stopTtl, Duration.ofHours(6));
        emptyStopTtl = positiveOrDefault(emptyStopTtl, Duration.ofMinutes(5));
        ttlJitterRatio = ttlJitterRatio == null ? 0.1 : ttlJitterRatio;
        if (ttlJitterRatio < 0 || ttlJitterRatio > 0.5) {
            throw new IllegalArgumentException("정류장 캐시 TTL 지터 비율은 0에서 0.5 사이여야 합니다.");
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
