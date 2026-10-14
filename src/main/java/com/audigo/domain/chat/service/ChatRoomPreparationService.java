package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatRoomPreparationResponse;
import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomMember;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import com.audigo.domain.user.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomPreparationService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    public ChatRoomPreparationService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomMemberRepository chatRoomMemberRepository
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatRoomMemberRepository = chatRoomMemberRepository;
    }

    @Transactional
    public PreparationResult prepareForMatchConnection(
            Long matchConnectionId,
            User requester,
            User targetUser
    ) {
        Optional<ChatRoom> existingRoom = chatRoomRepository.findByMatchConnectionId(matchConnectionId);
        if (existingRoom.isPresent()) {
            return result(existingRoom.get(), false);
        }

        ChatRoom room = chatRoomRepository.save(
                ChatRoom.createForMatchConnection(matchConnectionId, chatName(requester, targetUser))
        );
        chatRoomMemberRepository.saveAll(List.of(
                ChatRoomMember.create(room, requester),
                ChatRoomMember.create(room, targetUser)
        ));
        chatRoomMemberRepository.flush();

        return result(room, true);
    }

    private PreparationResult result(ChatRoom room, boolean created) {
        List<Long> memberUserIds = chatRoomMemberRepository.findActiveUserIds(room.getId());
        return new PreparationResult(ChatRoomPreparationResponse.from(room, memberUserIds), created);
    }

    private String chatName(User requester, User targetUser) {
        return requester.nickname() + " · " + targetUser.nickname();
    }

    public record PreparationResult(ChatRoomPreparationResponse response, boolean created) {
    }
}
