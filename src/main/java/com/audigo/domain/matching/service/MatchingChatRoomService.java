package com.audigo.domain.matching.service;

import com.audigo.domain.chat.dto.ChatRoomPreparationResponse;
import com.audigo.domain.chat.service.ChatRoomPreparationService;
import com.audigo.domain.chat.service.ChatRoomPreparationService.PreparationResult;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingChatRoomService {

    private final MatchConnectionRepository matchConnectionRepository;
    private final ChatRoomPreparationService chatRoomPreparationService;

    public MatchingChatRoomService(
            MatchConnectionRepository matchConnectionRepository,
            ChatRoomPreparationService chatRoomPreparationService
    ) {
        this.matchConnectionRepository = matchConnectionRepository;
        this.chatRoomPreparationService = chatRoomPreparationService;
    }

    @Transactional
    public ChatRoomPreparationResponse prepareChatRoom(Long userId, Long matchConnectionId) {
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

        return chatRoomPreparationService.prepareForMatchConnection(
                connection.getId(),
                connection.getMatchingRequest().getRequester(),
                connection.getTargetUser()
        );
    }

    private boolean isParticipant(Long userId, MatchConnection connection) {
        Long requesterId = connection.getMatchingRequest().getRequester().id();
        Long targetUserId = connection.getTargetUser().id();
        return Objects.equals(userId, requesterId) || Objects.equals(userId, targetUserId);
    }

}
