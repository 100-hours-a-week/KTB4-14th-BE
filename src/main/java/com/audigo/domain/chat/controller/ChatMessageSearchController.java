package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatMessageContextResponse;
import com.audigo.domain.chat.dto.ChatMessageSearchResponse;
import com.audigo.domain.chat.service.ChatMessageSearchService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatMessageSearchController {
    private final CurrentUser currentUser;
    private final ChatMessageSearchService service;

    public ChatMessageSearchController(CurrentUser currentUser, ChatMessageSearchService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @GetMapping("/api/v2/chat/rooms/{roomId}/messages/search")
    public ApiResponse<ChatMessageSearchResponse> search(
            @PathVariable String roomId,
            @RequestParam String keyword,
            @RequestParam(required = false) String cursor_time,
            @RequestParam(required = false) String cursor_id
    ) {
        return ApiResponse.of("chat_message_search_success",
                service.search(currentUser.id(), roomId, keyword, cursor_time, cursor_id));
    }

    @GetMapping("/api/v2/chat/rooms/{roomId}/messages/{chatId}/context")
    public ApiResponse<ChatMessageContextResponse> context(
            @PathVariable String roomId,
            @PathVariable String chatId
    ) {
        return ApiResponse.of("chat_message_context_success",
                service.context(currentUser.id(), roomId, chatId));
    }
}
