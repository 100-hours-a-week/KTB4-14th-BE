package com.audigo.global.error;

import com.audigo.global.response.ApiResponse;
import com.audigo.global.logging.RequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "rate_limit_exceeded request_id={} method={} uri={} retry_after_seconds={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                exception.retryAfterSeconds()
        );
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(ApiResponse.of(ErrorCode.CHAT_MESSAGE_RATE_LIMIT_EXCEEDED.message(), null));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.errorCode();
        log.warn(
                "business_exception request_id={} method={} uri={} status={} error_code={} reason={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                errorCode.status().value(),
                errorCode.message(),
                exception.getMessage()
        );
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.of(errorCode.message(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = resolveValidationError(exception);
        FieldError fieldError = exception.getBindingResult().getFieldError();
        log.warn(
                "validation_failed request_id={} method={} uri={} status={} error_code={} field={} reason={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                errorCode.status().value(),
                errorCode.message(),
                fieldError == null ? "-" : fieldError.getField(),
                fieldError == null ? "-" : fieldError.getDefaultMessage()
        );
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.of(errorCode.message(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        log.warn(
                "request_body_unreadable request_id={} method={} uri={} status={} error_code={} reason={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                errorCode.status().value(),
                errorCode.message(),
                exception.getMessage()
        );
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.of(errorCode.message(), null));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncResponseNotUsable(
            AsyncRequestNotUsableException exception,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // A ServletResponse argument marks this void result as handled, without writing to the broken stream.
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        // Do not pass the wrapper itself: Spring's helper treats every wrapper of this type as a disconnect.
        boolean disconnected = DisconnectedClientHelper.isClientDisconnectedException(exception.getCause())
                || "Servlet container error notification for disconnected client".equals(exception.getMessage());
        if (disconnected) {
            log.info("async_client_disconnected request_id={} method={} uri={} exception_type={} cause_type={} reason={}",
                    requestId(request), request.getMethod(), request.getRequestURI(),
                    exception.getClass().getName(), cause.getClass().getName(), cause.getMessage());
            log.debug("async_client_disconnected_details request_id={}", requestId(request), exception);
            return;
        }

        // An unusable response is not always a client disconnect. Keep unknown failures visible, but do not write.
        log.error("async_response_unusable request_id={} method={} uri={} exception_type={} cause_type={} reason={}",
                requestId(request), request.getMethod(), request.getRequestURI(),
                exception.getClass().getName(), cause.getClass().getName(), cause.getMessage(), exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        log.error(
                "unexpected_exception request_id={} method={} uri={} status={} error_code={} reason={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                errorCode.status().value(),
                errorCode.message(),
                exception.getMessage(),
                exception
        );
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.of(errorCode.message(), null));
    }

    private ErrorCode resolveValidationError(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        if (fieldError == null) {
            return ErrorCode.VALIDATION_FAILED;
        }
        if ("provider".equals(fieldError.getField()) && "NotBlank".equals(fieldError.getCode())) {
            return ErrorCode.PROVIDER_REQUIRED;
        }
        if ("authorizationCode".equals(fieldError.getField()) && "NotBlank".equals(fieldError.getCode())) {
            return ErrorCode.AUTHORIZATION_CODE_REQUIRED;
        }
        if ("nickname".equals(fieldError.getField()) && "NotBlank".equals(fieldError.getCode())) {
            return ErrorCode.INVALID_REQUEST;
        }
        return ErrorCode.VALIDATION_FAILED;
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId instanceof String value) {
            return value;
        }
        return "-";
    }
}
