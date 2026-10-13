package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailResponse;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.MessageDetailRow;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.RoomDetailRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomDetailService {

    private static final int DEFAULT_SIZE = 20;

    private final ChatRoomDetailRepository chatRoomDetailRepository;
    private final ChatPresenceRegistry chatPresenceRegistry;

    public ChatRoomDetailService(
            ChatRoomDetailRepository chatRoomDetailRepository,
            ChatPresenceRegistry chatPresenceRegistry
    ) {
        this.chatRoomDetailRepository = chatRoomDetailRepository;
        this.chatPresenceRegistry = chatPresenceRegistry;
    }

    @Transactional(readOnly = true)
    public ChatRoomDetailResponse getDetail(Long userId, ChatRoomDetailQuery query) {
        RoomDetailRow room = chatRoomDetailRepository.findAccessibleRoom(query.roomId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        int pageSize = query.size() == null ? DEFAULT_SIZE : query.size();
        List<MessageDetailRow> fetchedMessages = chatRoomDetailRepository.findMessages(
                room.roomId(),
                userId,
                room.opponentUserId(),
                query.cursorTime(),
                query.cursorId(),
                pageSize + 1
        );
        boolean hasNext = fetchedMessages.size() > pageSize;
        List<MessageDetailRow> pageMessages = hasNext
                ? fetchedMessages.subList(0, pageSize)
                : fetchedMessages;

        List<ChatRoomDetailResponse.Message> chats = new ArrayList<>(pageMessages.size());
        for (MessageDetailRow message : pageMessages) {
            chats.add(new ChatRoomDetailResponse.Message(
                    message.chatId(),
                    message.messageSequence(),
                    message.senderId(),
                    message.content(),
                    message.sentAt().atOffset(ZoneOffset.UTC),
                    message.isRead()
            ));
        }
        Collections.reverse(chats);

        MessageDetailRow oldestMessage = pageMessages.isEmpty()
                ? null
                : pageMessages.get(pageMessages.size() - 1);
        return new ChatRoomDetailResponse(
                room.roomId(),
                room.status(),
                new ChatRoomDetailResponse.Opponent(
                        room.opponentUserId(),
                        room.opponentChatName(),
                        room.opponentProfileImageUrl(),
                        chatPresenceRegistry.isOnline(room.opponentUserId())
                ),
                chats,
                chats.size(),
                hasNext ? oldestMessage.sentAt().atOffset(ZoneOffset.UTC) : null,
                hasNext ? oldestMessage.chatId() : null,
                hasNext
        );
    }
}
