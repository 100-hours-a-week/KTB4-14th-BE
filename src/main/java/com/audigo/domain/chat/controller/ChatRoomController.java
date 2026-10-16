package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatRoomListQuery;
import com.audigo.domain.chat.dto.ChatRoomListResponse;
import com.audigo.domain.chat.service.ChatRoomListService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatRoomController {

    private final CurrentUser currentUser;
    private final ChatRoomListService chatRoomListService;

    public ChatRoomController(CurrentUser currentUser, ChatRoomListService chatRoomListService) {
        this.currentUser = currentUser;
        this.chatRoomListService = chatRoomListService;
    }

    @GetMapping("/api/v2/chat/rooms")
    public ApiResponse<ChatRoomListResponse> list(
            @RequestParam(name = "chat_name", required = false) String chatName,
            @RequestParam(required = false) String size,
            @RequestParam(name = "cursor_time", required = false) String cursorTime,
            @RequestParam(name = "cursor_id", required = false) String cursorId
    ) {
        ChatRoomListQuery query = ChatRoomListQuery.from(chatName, size, cursorTime, cursorId);
        return ApiResponse.of(
                "chat_rooms_loading_success",
                chatRoomListService.list(currentUser.id(), query)
        );
    }
}
