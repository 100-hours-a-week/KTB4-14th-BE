package com.audigo.domain.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "audigo.chat.presence")
public record ChatPresenceProperties(
        @DefaultValue("15s") Duration renewalInterval,
        @DefaultValue("45s") Duration leaseDuration,
        @DefaultValue("90s") Duration keyTtl
) {

    public ChatPresenceProperties {
        requirePositive("renewal-interval", renewalInterval);
        requirePositive("lease-duration", leaseDuration);
        requirePositive("key-ttl", keyTtl);

        if (renewalInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "audigo.chat.presence.renewal-interval must be shorter than lease-duration"
            );
        }
        if (keyTtl.compareTo(leaseDuration) <= 0) {
            throw new IllegalArgumentException(
                    "audigo.chat.presence.key-ttl must be longer than lease-duration"
            );
        }
    }

    private static void requirePositive(String propertyName, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "audigo.chat.presence." + propertyName + " must be positive"
            );
        }
    }
}
