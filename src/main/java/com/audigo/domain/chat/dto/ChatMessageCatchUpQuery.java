package com.audigo.domain.chat.dto;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;

public record ChatMessageCatchUpQuery(Long roomId, long afterMessageSequence, int size) {

    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 100;

    public static ChatMessageCatchUpQuery from(String rawRoomId, String rawAfterMessageSequence, String rawSize) {
        long roomId = parseLong(rawRoomId, 1);
        long afterMessageSequence = rawAfterMessageSequence == null
                ? 0
                : parseLong(rawAfterMessageSequence, 0);
        int size = rawSize == null ? DEFAULT_SIZE : parseSize(rawSize);
        return new ChatMessageCatchUpQuery(roomId, afterMessageSequence, size);
    }

    private static long parseLong(String value, long minimum) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum) {
                throw invalidRequest();
            }
            return parsed;
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidRequest();
        }
    }

    private static int parseSize(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_SIZE) {
                throw invalidRequest();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private static BusinessException invalidRequest() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
