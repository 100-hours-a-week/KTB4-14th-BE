package com.audigo.domain.chat.dto;

import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public record ChatRoomDetailResponse(
        @JsonProperty("chat_room_id") Long chatRoomId,
        ChatRoomStatus status,
        Opponent opponent,
        List<Message> chats,
        int size,
        @JsonProperty("chat_cursor_time") OffsetDateTime chatCursorTime,
        @JsonProperty("chat_cursor_id") Long chatCursorId,
        @JsonProperty("has_next") boolean hasNext
) {

    public ChatRoomDetailResponse {
        chats = List.copyOf(chats);
    }

    public record Opponent(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("chat_name") String chatName,
            @JsonProperty("profile_image_url") String profileImageUrl,
            @JsonProperty("is_online") boolean isOnline
    ) {
    }

    public record Message(
            @JsonProperty("chat_id") Long chatId,
            @JsonProperty("message_seq") long messageSequence,
            @JsonProperty("sender_id") Long senderId,
            String content,
            @JsonProperty("sent_at") OffsetDateTime sentAt,
            @JsonProperty("is_read") boolean isRead
    ) {
    }
}
