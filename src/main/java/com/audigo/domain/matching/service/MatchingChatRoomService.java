package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingChatRoomResponse;
import com.audigo.domain.matching.entity.ChatRoom;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.ChatRoomRepository;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingChatRoomService {

    private final MatchConnectionRepository matchConnectionRepository;
    private final ChatRoomRepository chatRoomRepository;

    public MatchingChatRoomService(
            MatchConnectionRepository matchConnectionRepository,
            ChatRoomRepository chatRoomRepository
    ) {
        this.matchConnectionRepository = matchConnectionRepository;
        this.chatRoomRepository = chatRoomRepository;
    }

    @Transactional
    public MatchingChatRoomResponse prepareChatRoom(Long userId, Long matchConnectionId) {
        MatchConnection connection = matchConnectionRepository.findById(matchConnectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCH_CONNECTION_NOT_FOUND));
        validateAccessible(userId, connection);
        if (connection.getStatus() != MatchConnectionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ChatRoom chatRoom = chatRoomRepository.findByMatchConnection_Id(connection.getId())
                .orElseGet(() -> chatRoomRepository.save(createChatRoom(connection)));
        return MatchingChatRoomResponse.from(chatRoom);
    }

    private ChatRoom createChatRoom(MatchConnection connection) {
        User requester = connection.getMatchingRequest().getRequester();
        User targetUser = connection.getTargetUser();
        return ChatRoom.create(connection, chatName(requester, targetUser), requester, targetUser);
    }

    private void validateAccessible(Long userId, MatchConnection connection) {
        Long requesterId = connection.getMatchingRequest().getRequester().id();
        Long targetUserId = connection.getTargetUser().id();
        if (!Objects.equals(userId, requesterId) && !Objects.equals(userId, targetUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private String chatName(User requester, User targetUser) {
        return requester.nickname() + " · " + targetUser.nickname();
    }
}
