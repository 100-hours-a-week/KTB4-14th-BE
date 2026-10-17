package com.audigo.global.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    // 컷오버 중 보류됐던 요청 표시 (PendingWriteQueue가 설정)
    public static final String HELD_ATTRIBUTE = "migrationHeld";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAt = System.nanoTime();
        Throwable failure = null;

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);

        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure == null && request.isAsyncStarted()) {
                // 보류·비동기 요청은 응답이 실제로 끝난 시점의 status와 duration을 기록한다
                logOnAsyncCompletion(request, response, requestId, startedAt, currentUserId());
            } else {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                int status = response.getStatus();
                if (failure != null && status < 500) {
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                }
                logRequest(request, requestId, status, durationMs, currentUserId(), failure);
            }
            MDC.remove("request_id");
        }
    }

    private void logOnAsyncCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            String requestId,
            long startedAt,
            Long userId
    ) {
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                MDC.put("request_id", requestId);
                try {
                    logRequest(request, requestId, response.getStatus(), durationMs, userId, event.getThrowable());
                } finally {
                    MDC.remove("request_id");
                }
            }

            @Override
            public void onTimeout(AsyncEvent event) {
            }

            @Override
            public void onError(AsyncEvent event) {
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        });
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }

    private void logRequest(
            HttpServletRequest request,
            String requestId,
            int status,
            long durationMs,
            Long userId,
            Throwable failure
    ) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String reason = failure == null ? "-" : failure.getClass().getSimpleName();
        String held = Boolean.TRUE.equals(request.getAttribute(HELD_ATTRIBUTE)) ? " held=true" : "";

        if (status >= 500) {
            log.error(
                    "request_completed request_id={} method={} uri={} status={} duration_ms={} user_id={} reason={}{}",
                    requestId,
                    method,
                    uri,
                    status,
                    durationMs,
                    userId,
                    reason,
                    held
            );
            return;
        }
        if (status >= 400) {
            log.warn(
                    "request_completed request_id={} method={} uri={} status={} duration_ms={} user_id={} reason={}{}",
                    requestId,
                    method,
                    uri,
                    status,
                    durationMs,
                    userId,
                    reason,
                    held
            );
            return;
        }
        log.info(
                "request_completed request_id={} method={} uri={} status={} duration_ms={} user_id={}{}",
                requestId,
                method,
                uri,
                status,
                durationMs,
                userId,
                held
        );
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
