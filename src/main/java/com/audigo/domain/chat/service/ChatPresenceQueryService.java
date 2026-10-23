package com.audigo.domain.chat.service;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Collection;
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
        try {
            return redisChatPresenceStore.isOnline(userId);
        } catch (DataAccessException exception) {
            log.error(
                    "chat_presence_redis_query_failed request_id={} operation=single",
                    MDC.get("request_id"),
                    exception
            );
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public Map<Long, Boolean> findOnlineStatuses(Collection<Long> userIds) {
        try {
            return redisChatPresenceStore.findOnlineStatuses(userIds);
        } catch (DataAccessException exception) {
            log.error(
                    "chat_presence_redis_query_failed request_id={} operation=batch requested_user_count={}",
                    MDC.get("request_id"),
                    userIds.size(),
                    exception
            );
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
