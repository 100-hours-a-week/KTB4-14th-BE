package com.audigo.domain.auth.cookie;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audigo.auth.cookie")
public record AuthCookieProperties(
        String accessTokenName,
        String refreshTokenName,
        boolean httpOnly,
        boolean secure,
        String sameSite,
        String path
) {
    public AuthCookieProperties {
        if (accessTokenName == null || accessTokenName.isBlank()) {
            accessTokenName = "audigo_access_token";
        }
        if (refreshTokenName == null || refreshTokenName.isBlank()) {
            refreshTokenName = "audigo_refresh_token";
        }
        if (sameSite == null || sameSite.isBlank()) {
            sameSite = "Lax";
        }
        if (path == null || path.isBlank()) {
            path = "/";
        }
    }
}
