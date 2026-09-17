package com.audigo.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    PROVIDER_REQUIRED(HttpStatus.BAD_REQUEST, "provider_required"),
    AUTHORIZATION_CODE_REQUIRED(HttpStatus.BAD_REQUEST, "authorization_code_required"),
    REFRESH_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "refresh_token_required"),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "unsupported_oauth_provider"),
    OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "oauth_authentication_failed"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "invalid_refresh_token"),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "refresh_token_not_found"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    USER_INACTIVE(HttpStatus.FORBIDDEN, "user_inactive"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user_not_found"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "notification_not_found"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error"),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "external_api_error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");

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
