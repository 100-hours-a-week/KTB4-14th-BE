package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatRoomReadRequest;
import com.audigo.domain.chat.dto.ChatRoomReadResponse;
import com.audigo.domain.chat.service.ChatMessageReadService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatRoomReadController {

    private final CurrentUser currentUser;
    private final ChatMessageReadService chatMessageReadService;

    public ChatRoomReadController(CurrentUser currentUser, ChatMessageReadService chatMessageReadService) {
        this.currentUser = currentUser;
        this.chatMessageReadService = chatMessageReadService;
    }

    @PatchMapping("/api/v2/chat/rooms/{roomId}/read")
    public ApiResponse<ChatRoomReadResponse> markRead(
            @PathVariable String roomId,
            @RequestBody ChatRoomReadRequest request
    ) {
        return ApiResponse.of(
                "chat_room_read_success",
                chatMessageReadService.markRead(
                        currentUser.id(),
                        parseRoomId(roomId),
                        request.validatedLastReadChatId()
                )
        );
    }

    private Long parseRoomId(String value) {
        try {
            long roomId = Long.parseLong(value);
            if (roomId < 1) {
                throw new NumberFormatException("roomId must be positive");
            }
            return roomId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
