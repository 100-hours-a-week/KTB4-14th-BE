package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.service.ChatRoomMembershipService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatRoomMembershipController {

    private final CurrentUser currentUser;
    private final ChatRoomMembershipService chatRoomMembershipService;

    public ChatRoomMembershipController(
            CurrentUser currentUser,
            ChatRoomMembershipService chatRoomMembershipService
    ) {
        this.currentUser = currentUser;
        this.chatRoomMembershipService = chatRoomMembershipService;
    }

    @DeleteMapping("/api/v2/chat/rooms/{room_id}")
    public ResponseEntity<Void> leave(@PathVariable("room_id") String roomId) {
        chatRoomMembershipService.leave(currentUser.id(), parseRoomId(roomId));
        return ResponseEntity.noContent().build();
    }

    private Long parseRoomId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException("roomId must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
