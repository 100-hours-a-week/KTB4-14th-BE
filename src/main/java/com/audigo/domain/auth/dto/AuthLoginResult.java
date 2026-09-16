package com.audigo.domain.auth.dto;

public record AuthLoginResult(
        AuthTokens tokens,
        AuthUserResponse response
) {
}
