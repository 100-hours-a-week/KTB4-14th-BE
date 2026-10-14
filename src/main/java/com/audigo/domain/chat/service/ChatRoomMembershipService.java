package com.audigo.domain.chat.service;

import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomMember;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomMembershipService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    public ChatRoomMembershipService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomMemberRepository chatRoomMemberRepository
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatRoomMemberRepository = chatRoomMemberRepository;
    }

    @Transactional
    public void leave(Long userId, Long roomId) {
        // Keep the same room-first lock order as message send so ENDED wins for a waiting send request.
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_CHAT_ROOM));
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_CHAT_ROOM));

        if (room.getStatus() == ChatRoomStatus.ENDED) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ENDED);
        }

        member.leave(LocalDateTime.now(ZoneOffset.UTC));
        room.end();
    }
}
