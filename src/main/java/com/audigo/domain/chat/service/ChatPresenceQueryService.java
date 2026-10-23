package com.audigo.domain.chat.service;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Collection;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ChatPresenceQueryService {

    private final RedisChatPresenceStore redisChatPresenceStore;

    public ChatPresenceQueryService(RedisChatPresenceStore redisChatPresenceStore) {
        this.redisChatPresenceStore = redisChatPresenceStore;
    }

    public boolean isOnline(Long userId) {
        try {
            return redisChatPresenceStore.isOnline(userId);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public Map<Long, Boolean> findOnlineStatuses(Collection<Long> userIds) {
        try {
            return redisChatPresenceStore.findOnlineStatuses(userIds);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
