package com.audigo.domain.chat.service;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ChatPresenceQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChatPresenceQueryService.class);

    private final RedisChatPresenceStore redisChatPresenceStore;

    public ChatPresenceQueryService(RedisChatPresenceStore redisChatPresenceStore) {
        this.redisChatPresenceStore = redisChatPresenceStore;
    }

    public boolean isOnline(Long userId) {
        long startedAt = System.nanoTime();
        try {
            return redisChatPresenceStore.isOnline(userId);
        } catch (DataAccessException exception) {
            logQueryFailure("single", 1, 1, startedAt, exception);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public Map<Long, Boolean> findOnlineStatuses(Collection<Long> userIds) {
        int requestedUserCount = userIds.size();
        int uniqueUserCount = new LinkedHashSet<>(userIds).size();
        long startedAt = System.nanoTime();
        try {
            return redisChatPresenceStore.findOnlineStatuses(userIds);
        } catch (DataAccessException exception) {
            logQueryFailure("batch", requestedUserCount, uniqueUserCount, startedAt, exception);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void logQueryFailure(
            String operation,
            int requestedUserCount,
            int uniqueUserCount,
            long startedAt,
            DataAccessException exception
    ) {
        ChatPresenceRedisLookupException lookupException = exception instanceof ChatPresenceRedisLookupException value
                ? value
                : null;
        Throwable rootCause = rootCause(exception);

        log.error(
                "chat_presence_redis_query_failed request_id={} operation={} script=online_status "
                        + "requested_user_count={} unique_user_count={} query_index={} query_total={} key_count={} "
                        + "redis_call_duration_ms={} total_duration_ms={} exception_class={} root_cause_class={} "
                        + "root_cause_message={}",
                MDC.get("request_id"),
                operation,
                requestedUserCount,
                uniqueUserCount,
                lookupException == null ? "unknown" : lookupException.queryIndex(),
                lookupException == null ? "unknown" : lookupException.queryTotal(),
                lookupException == null ? "unknown" : 1,
                lookupException == null ? "unknown" : lookupException.elapsedMillis(),
                elapsedMillis(startedAt),
                exception.getClass().getSimpleName(),
                rootCause.getClass().getSimpleName(),
                safeMessage(rootCause.getMessage()),
                exception
        );
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String sanitized = message
                .replaceAll("(?i)(password|token|authorization)(\\s*[=:]\\s*)[^\\s,;]+", "$1$2[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
