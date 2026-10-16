package com.audigo.domain.matching.controller;

import com.audigo.domain.chat.dto.ChatRoomPreparationResponse;
import com.audigo.domain.matching.service.MatchingChatRoomService;
import com.audigo.domain.chat.service.ChatRoomPreparationService.PreparationResult;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/match-connections")
public class MatchingChatRoomController {

    private final CurrentUser currentUser;
    private final MatchingChatRoomService matchingChatRoomService;

    public MatchingChatRoomController(
            CurrentUser currentUser,
            MatchingChatRoomService matchingChatRoomService
    ) {
        this.currentUser = currentUser;
        this.matchingChatRoomService = matchingChatRoomService;
    }

    @PostMapping("/{matchConnectionId}/chat-room")
    public ResponseEntity<ApiResponse<ChatRoomPreparationResponse>> prepareChatRoom(
            @PathVariable String matchConnectionId
    ) {
        PreparationResult result = matchingChatRoomService.prepareChatRoomWithStatus(
                currentUser.id(),
                parseMatchConnectionId(matchConnectionId)
        );
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.of("match_chat_room_ready", result.response()));
    }

    private Long parseMatchConnectionId(String value) {
        try {
            long matchConnectionId = Long.parseLong(value);
            if (matchConnectionId < 1) {
                throw new NumberFormatException("matchConnectionId must be positive");
            }
            return matchConnectionId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
