package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailResponse;
import com.audigo.domain.chat.dto.ChatRoomReadRequest;
import com.audigo.domain.chat.dto.ChatRoomReadResponse;
import com.audigo.domain.chat.service.ChatMessageReadService;
import com.audigo.domain.chat.service.ChatRoomDetailService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatRoomDetailController {

    private final CurrentUser currentUser;
    private final ChatRoomDetailService chatRoomDetailService;
    private final ChatMessageReadService chatMessageReadService;

    public ChatRoomDetailController(
            CurrentUser currentUser,
            ChatRoomDetailService chatRoomDetailService,
            ChatMessageReadService chatMessageReadService
    ) {
        this.currentUser = currentUser;
        this.chatRoomDetailService = chatRoomDetailService;
        this.chatMessageReadService = chatMessageReadService;
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
                        request == null ? null : request.validatedLastReadChatId()
                )
        );
    }

    private Long parseRoomId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException("roomId must be positive");
            }
            return parsed;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
