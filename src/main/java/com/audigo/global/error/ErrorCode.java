package com.audigo.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    INVALID_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "invalid_oauth_provider"),
    KAKAO_OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "kakao_oauth_failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user_not_found");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
