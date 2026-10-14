package com.audigo.domain.matching.service;

import com.audigo.domain.matching.dto.MatchingChatRoomResponse;
import com.audigo.domain.matching.entity.ChatRoom;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchingChatRoomRepository;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingChatRoomService {

    private final MatchConnectionRepository matchConnectionRepository;
    private final MatchingChatRoomRepository chatRoomRepository;

    public MatchingChatRoomService(
            MatchConnectionRepository matchConnectionRepository,
            MatchingChatRoomRepository chatRoomRepository
    ) {
        this.matchConnectionRepository = matchConnectionRepository;
        this.chatRoomRepository = chatRoomRepository;
    }

    @Transactional
    public MatchingChatRoomResponse prepareChatRoom(Long userId, Long matchConnectionId) {
        return prepareChatRoomInternal(userId, matchConnectionId).response();
    }

    @Transactional
    public PreparationResult prepareChatRoomWithStatus(Long userId, Long matchConnectionId) {
        return prepareChatRoomInternal(userId, matchConnectionId);
    }

    private PreparationResult prepareChatRoomInternal(Long userId, Long matchConnectionId) {
        MatchConnection connection = matchConnectionRepository.findByIdForUpdate(matchConnectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCH_CONNECTION_NOT_FOUND));
        if (!isParticipant(userId, connection) || connection.getStatus() != MatchConnectionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MATCH_CONNECTION_NOT_FOUND);
        }

        Optional<ChatRoom> existingRoom = chatRoomRepository.findByMatchConnection_Id(connection.getId());
        if (existingRoom.isPresent()) {
            return new PreparationResult(MatchingChatRoomResponse.from(existingRoom.get()), false);
        }

        ChatRoom createdRoom = chatRoomRepository.save(createChatRoom(connection));
        return new PreparationResult(MatchingChatRoomResponse.from(createdRoom), true);
    }

    private ChatRoom createChatRoom(MatchConnection connection) {
        User requester = connection.getMatchingRequest().getRequester();
        User targetUser = connection.getTargetUser();
        return ChatRoom.create(connection, chatName(requester, targetUser), requester, targetUser);
    }

    private boolean isParticipant(Long userId, MatchConnection connection) {
        Long requesterId = connection.getMatchingRequest().getRequester().id();
        Long targetUserId = connection.getTargetUser().id();
        return Objects.equals(userId, requesterId) || Objects.equals(userId, targetUserId);
    }

    private String chatName(User requester, User targetUser) {
        return requester.nickname() + " · " + targetUser.nickname();
    }

    public record PreparationResult(MatchingChatRoomResponse response, boolean created) {
    }
}
