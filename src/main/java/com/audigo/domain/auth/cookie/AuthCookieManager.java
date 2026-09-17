package com.audigo.domain.auth.cookie;

import com.audigo.domain.auth.dto.AuthTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieManager {

    private final AuthCookieProperties properties;

    public AuthCookieManager(AuthCookieProperties properties) {
        this.properties = properties;
    }

    public HttpHeaders issue(AuthTokens tokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, createCookie(
                properties.accessTokenName(),
                tokens.accessToken(),
                tokens.accessTokenExpiresIn()
        ).toString());
        headers.add(HttpHeaders.SET_COOKIE, createCookie(
                properties.refreshTokenName(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresIn()
        ).toString());
        return headers;
    }

    public HttpHeaders clear() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, createCookie(properties.accessTokenName(), "", 0).toString());
        headers.add(HttpHeaders.SET_COOKIE, createCookie(properties.refreshTokenName(), "", 0).toString());
        return headers;
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        return readCookie(request, properties.accessTokenName());
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        return readCookie(request, properties.refreshTokenName());
    }

    private ResponseCookie createCookie(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(properties.httpOnly())
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.path())
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
