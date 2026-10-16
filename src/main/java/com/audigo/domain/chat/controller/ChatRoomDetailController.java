package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailResponse;
import com.audigo.domain.chat.service.ChatRoomDetailService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatRoomDetailController {

    private final CurrentUser currentUser;
    private final ChatRoomDetailService chatRoomDetailService;

    public ChatRoomDetailController(
            CurrentUser currentUser,
            ChatRoomDetailService chatRoomDetailService
    ) {
        this.currentUser = currentUser;
        this.chatRoomDetailService = chatRoomDetailService;
    }

    @GetMapping("/api/v2/chat/rooms/{roomId}")
    public ApiResponse<ChatRoomDetailResponse> detail(
            @PathVariable String roomId,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "chat_cursor_time", required = false) String cursorTime,
            @RequestParam(name = "chat_cursor_id", required = false) String cursorId
    ) {
        Long userId = currentUser.id();
        ChatRoomDetailQuery query = ChatRoomDetailQuery.from(roomId, size, cursorTime, cursorId);
        return ApiResponse.of(
                "chat_room_detail_loading_success",
                chatRoomDetailService.getDetail(userId, query)
        );
    }
}
