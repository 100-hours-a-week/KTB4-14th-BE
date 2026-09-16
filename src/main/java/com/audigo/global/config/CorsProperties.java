package com.audigo.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
    public List<String> resolvedAllowedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return List.of("http://localhost:5173");
        }
        return allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toList();
    }
}
