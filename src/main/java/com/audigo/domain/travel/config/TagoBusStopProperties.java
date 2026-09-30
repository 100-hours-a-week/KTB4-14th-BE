package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.transit-stop.tago")
public record TagoBusStopProperties(
        String baseUrl,
        String serviceKey
) {

    public TagoBusStopProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        serviceKey = serviceKey == null ? "" : serviceKey.trim();
    }
}
