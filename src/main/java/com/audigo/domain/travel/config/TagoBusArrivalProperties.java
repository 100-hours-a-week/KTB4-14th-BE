package com.audigo.domain.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.transit-arrival.tago")
public record TagoBusArrivalProperties(
        String baseUrl,
        String serviceKey
) {

    public TagoBusArrivalProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        serviceKey = serviceKey == null ? "" : serviceKey.trim();
    }
}
