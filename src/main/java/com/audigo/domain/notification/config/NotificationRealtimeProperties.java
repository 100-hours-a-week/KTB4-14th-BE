package com.audigo.domain.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.notification.realtime")
public record NotificationRealtimeProperties(
        String applicationName,
        String environment,
        String channel
) {

    public NotificationRealtimeProperties {
        applicationName = requireText("application-name", applicationName);
        environment = requireText("environment", environment);
        String namespace = "audigo:" + applicationName + ":" + environment + ":notification:";

        if (channel == null || channel.isBlank()) {
            channel = namespace + "realtime";
        } else {
            channel = channel.trim();
            if (!channel.startsWith(namespace)) {
                throw new IllegalArgumentException(
                        "audigo.notification.realtime.channel must start with " + namespace
                );
            }
        }
    }

    private static String requireText(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "audigo.notification.realtime." + propertyName + " must not be blank"
            );
        }
        return value.trim();
    }
}
