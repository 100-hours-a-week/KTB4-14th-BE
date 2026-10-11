package com.audigo.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatRoomListResponse(
        List<ChatRoomItemResponse> rooms,
        int size,
        LocalDateTime cursorTime,
        Long cursorId,
        boolean hasNext
) {
    public ChatRoomListResponse {
        rooms = List.copyOf(rooms);
    }
}
