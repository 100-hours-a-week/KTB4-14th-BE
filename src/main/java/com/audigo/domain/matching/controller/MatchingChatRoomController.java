package com.audigo.domain.matching.controller;

import com.audigo.domain.matching.dto.MatchingChatRoomResponse;
import com.audigo.domain.matching.service.MatchingChatRoomService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MatchingChatRoomResponse> prepareChatRoom(
            @PathVariable Long matchConnectionId
    ) {
        return ApiResponse.of(
                "match_chat_room_ready",
                matchingChatRoomService.prepareChatRoom(currentUser.id(), matchConnectionId)
        );
    }
}
