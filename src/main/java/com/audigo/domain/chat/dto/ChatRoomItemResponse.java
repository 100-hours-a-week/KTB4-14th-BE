package com.audigo.domain.chat.dto;

import com.audigo.domain.chat.entity.ChatRoomStatus;
import java.time.LocalDateTime;

public record ChatRoomItemResponse(
        Long roomId,
        String chatName,
        ChatRoomStatus status,
        boolean isOnline,
        String lastMessage,
        LocalDateTime lastMessageAt,
        String profileImageUrl,
        int unreadCount
) {
}
