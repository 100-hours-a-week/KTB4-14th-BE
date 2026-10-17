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
    REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "region_not_found"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "notification_not_found"),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "chat_room_not_found"),
    NOT_FOUND_CHAT_ROOM(HttpStatus.NOT_FOUND, "not_found_chat_room"),
    MATCHING_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "matching_request_not_found"),
    MATCH_CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "match_connection_not_found"),
    INVALID_NOTIFICATION_REQUEST(HttpStatus.BAD_REQUEST, "invalid_notification_request"),
    DUPLICATED_REQUIRED_PLACE(HttpStatus.CONFLICT, "duplicated_required_place"),
    CHAT_ROOM_ENDED(HttpStatus.CONFLICT, "chat_room_ended"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "idempotency_key_conflict"),
    TRAVEL_GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "travel_generation_in_progress"),
    OVERLAPPING_CONFIRMED_TRAVEL(HttpStatus.CONFLICT, "overlapping_confirmed_travel"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error"),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "external_api_error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable"),
    MIGRATION_HOLD_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "migration_hold_timeout"),
    MIGRATION_PENDING_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "migration_pending_queue_full"),
    MIGRATION_DISABLED(HttpStatus.FORBIDDEN, "migration_disabled"),
    MIGRATION_STALE_COMMAND(HttpStatus.CONFLICT, "migration_stale_command"),
    MIGRATION_ID_MISMATCH(HttpStatus.CONFLICT, "migration_id_mismatch"),
    MIGRATION_ATTEMPT_IN_PROGRESS(HttpStatus.CONFLICT, "migration_attempt_in_progress"),
    MIGRATION_INVALID_PHASE(HttpStatus.CONFLICT, "migration_invalid_phase"),
    MIGRATION_REMAINING_WORK(HttpStatus.CONFLICT, "migration_remaining_work"),
    MIGRATION_RESUME_TARGET_REQUIRED(HttpStatus.BAD_REQUEST, "migration_resume_target_required");
    MIGRATION_PENDING_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "migration_pending_queue_full");
    CHAT_MESSAGE_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "chat_message_rate_limit_exceeded"),
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
