package com.audigo.domain.chat.dto;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record SendChatMessageRequest(
        @JsonProperty("client_message_id") String clientMessageId,
        String content
) {

    public SendChatMessageCommand toCommand() {
        if (clientMessageId == null || content == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        UUID parsedClientMessageId;
        try {
            parsedClientMessageId = UUID.fromString(clientMessageId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!parsedClientMessageId.toString().equalsIgnoreCase(clientMessageId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String normalizedContent = stripUnicodeWhitespace(content);
        if (normalizedContent.isEmpty() || normalizedContent.codePointCount(0, normalizedContent.length()) > 500) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return new SendChatMessageCommand(parsedClientMessageId.toString(), normalizedContent);
    }

    private String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public record SendChatMessageCommand(String clientMessageId, String content) {
    }
}
