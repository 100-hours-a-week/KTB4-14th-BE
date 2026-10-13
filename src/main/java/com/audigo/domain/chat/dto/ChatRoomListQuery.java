package com.audigo.domain.chat.dto;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

public record ChatRoomListQuery(
        String chatName,
        Integer size,
        LocalDateTime cursorTime,
        Long cursorId
) {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    public static ChatRoomListQuery from(
            String chatName,
            String rawSize,
            String rawCursorTime,
            String rawCursorId
    ) {
        Integer size = parseSize(rawSize);
        LocalDateTime cursorTime = parseCursorTime(rawCursorTime);
        Long cursorId = parseCursorId(rawCursorId);

        if ((cursorTime == null) != (cursorId == null)) {
            throw invalidRequest();
        }

        return new ChatRoomListQuery(chatName, size, cursorTime, cursorId);
    }

    private static Integer parseSize(String rawSize) {
        if (rawSize == null) {
            return null;
        }

        try {
            int size = Integer.parseInt(rawSize.trim());
            if (size < MIN_SIZE || size > MAX_SIZE) {
                throw invalidRequest();
            }
            return size;
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private static LocalDateTime parseCursorTime(String rawCursorTime) {
        if (rawCursorTime == null) {
            return null;
        }

        try {
            return LocalDateTime.parse(rawCursorTime.trim());
        } catch (DateTimeParseException exception) {
            throw invalidRequest();
        }
    }

    private static Long parseCursorId(String rawCursorId) {
        if (rawCursorId == null) {
            return null;
        }

        try {
            long cursorId = Long.parseLong(rawCursorId.trim());
            if (cursorId <= 0) {
                throw invalidRequest();
            }
            return cursorId;
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private static BusinessException invalidRequest() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
