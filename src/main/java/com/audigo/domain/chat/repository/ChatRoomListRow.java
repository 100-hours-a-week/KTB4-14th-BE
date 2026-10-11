package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatRoomStatus;
import java.time.LocalDateTime;

public record ChatRoomListRow(
        Long roomId,
        Long otherUserId,
        String chatName,
        ChatRoomStatus status,
        LocalDateTime lastMessageAt,
        String profileImageUrl
) {
}
