package com.audigo.domain.chat.dto;

import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record ChatRoomPreparationResponse(
        @JsonProperty("chat_room_id") Long chatRoomId,
        @JsonProperty("match_connection_id") Long matchConnectionId,
        @JsonProperty("chat_name") String chatName,
        ChatRoomStatus status,
        @JsonProperty("member_user_ids") List<Long> memberUserIds,
        @JsonProperty("created_at") LocalDateTime createdAt
) {

    public ChatRoomPreparationResponse {
        memberUserIds = memberUserIds == null ? List.of() : List.copyOf(memberUserIds);
    }

    public static ChatRoomPreparationResponse from(ChatRoom room, List<Long> memberUserIds) {
        return new ChatRoomPreparationResponse(
                room.getId(),
                room.getMatchConnectionId(),
                room.getChatName(),
                room.getStatus(),
                memberUserIds,
                room.getCreatedAt()
        );
    }
}
