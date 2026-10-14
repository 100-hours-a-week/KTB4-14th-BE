package com.audigo.domain.chat.dto;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRoomReadRequest(
        @JsonProperty("last_read_chat_id") Long lastReadChatId
) {

    public Long validatedLastReadChatId() {
        if (lastReadChatId == null || lastReadChatId < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return lastReadChatId;
    }
}
