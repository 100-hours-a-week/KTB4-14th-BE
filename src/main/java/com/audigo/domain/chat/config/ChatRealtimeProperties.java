package com.audigo.domain.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.chat.realtime")
public record ChatRealtimeProperties(
        String applicationName,
        String environment,
        String channel
) {

    public ChatRealtimeProperties {
        applicationName = requireText("application-name", applicationName);
        environment = requireText("environment", environment);
        String namespace = "audigo:" + applicationName + ":" + environment + ":";

        if (channel == null || channel.isBlank()) {
            channel = namespace + "chat:realtime";
        } else {
            channel = channel.trim();
            if (!channel.startsWith(namespace)) {
                throw new IllegalArgumentException(
                        "audigo.chat.realtime.channel must start with " + namespace
                );
            }
        }
    }

    private static String requireText(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "audigo.chat.realtime." + propertyName + " must not be blank"
            );
        }
        return value.trim();
    }
}
