package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.ChatRoom;
import com.audigo.domain.matching.entity.ChatRoomMember;
import com.audigo.domain.matching.entity.ChatRoomStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record MatchingChatRoomResponse(
        @JsonProperty("chat_room_id") Long chatRoomId,
        @JsonProperty("match_connection_id") Long matchConnectionId,
        @JsonProperty("chat_name") String chatName,
        ChatRoomStatus status,
        @JsonProperty("member_user_ids") List<Long> memberUserIds,
        @JsonProperty("created_at") LocalDateTime createdAt
) {

    public MatchingChatRoomResponse {
        memberUserIds = memberUserIds == null ? List.of() : List.copyOf(memberUserIds);
    }

    public static MatchingChatRoomResponse from(ChatRoom chatRoom) {
        return new MatchingChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getMatchConnection().getId(),
                chatRoom.getChatName(),
                chatRoom.getStatus(),
                chatRoom.getMembers().stream()
                        .map(ChatRoomMember::getUser)
                        .map(user -> user.id())
                        .toList(),
                chatRoom.getCreatedAt()
        );
    }
}
