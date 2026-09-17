package com.audigo.global.error;

import com.audigo.global.response.ApiResponse;
import com.audigo.global.logging.RequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
