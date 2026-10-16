package com.audigo.domain.chat.dto;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public record ChatRoomDetailQuery(
        Long roomId,
        Integer size,
        LocalDateTime cursorTime,
        Long cursorId
) {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    public static ChatRoomDetailQuery from(
            String rawRoomId,
            String rawSize,
            String rawCursorTime,
            String rawCursorId
    ) {
        Long roomId = parsePositiveLong(rawRoomId);
        Integer size = parseSize(rawSize);
        LocalDateTime cursorTime = parseCursorTime(rawCursorTime);
        Long cursorId = parseOptionalPositiveLong(rawCursorId);

        if ((cursorTime == null) != (cursorId == null)) {
            throw invalidRequest();
        }

        return new ChatRoomDetailQuery(roomId, size, cursorTime, cursorId);
    }

    private static Integer parseSize(String rawSize) {
        if (rawSize == null) {
            return null;
        }

        try {
            int size = Integer.parseInt(rawSize);
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
            return OffsetDateTime.parse(rawCursorTime)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException exception) {
            throw invalidRequest();
        }
    }

    private static Long parseOptionalPositiveLong(String rawValue) {
        return rawValue == null ? null : parsePositiveLong(rawValue);
    }

    private static Long parsePositiveLong(String rawValue) {
        try {
            long value = Long.parseLong(rawValue);
            if (value < 1) {
                throw invalidRequest();
            }
            return value;
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidRequest();
        }
    }

    private static BusinessException invalidRequest() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
