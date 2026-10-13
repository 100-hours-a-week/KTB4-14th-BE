package com.audigo.domain.chat.controller;

import com.audigo.domain.chat.dto.ChatMessageCatchUpQuery;
import com.audigo.domain.chat.dto.ChatMessageCatchUpResponse;
import com.audigo.domain.chat.dto.ChatMessageResponse;
import com.audigo.domain.chat.dto.SendChatMessageRequest;
import com.audigo.domain.chat.service.ChatMessageService;
import com.audigo.domain.chat.service.ChatMessageService.SendResult;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatMessageController {

    private final CurrentUser currentUser;
    private final ChatMessageService chatMessageService;

    public ChatMessageController(CurrentUser currentUser, ChatMessageService chatMessageService) {
        this.currentUser = currentUser;
        this.chatMessageService = chatMessageService;
    }

    @PostMapping("/api/v2/chat/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> send(
            @PathVariable String roomId,
            @RequestBody SendChatMessageRequest request
    ) {
        Long parsedRoomId = parseRoomId(roomId);
        SendResult result = chatMessageService.sendWithStatus(
                currentUser.id(),
                parsedRoomId,
                request.toCommand()
        );
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.of("chat_message_send_success", result.response()));
    }

    @GetMapping("/api/v2/chat/rooms/{roomId}/messages")
    public ApiResponse<ChatMessageCatchUpResponse> catchUp(
            @PathVariable String roomId,
            @RequestParam(name = "after_message_seq", required = false) String afterMessageSequence,
            @RequestParam(name = "size", required = false) String size
    ) {
        ChatMessageCatchUpQuery query = ChatMessageCatchUpQuery.from(roomId, afterMessageSequence, size);
        return ApiResponse.of(
                "chat_messages_loading_success",
                chatMessageService.catchUp(currentUser.id(), query)
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
