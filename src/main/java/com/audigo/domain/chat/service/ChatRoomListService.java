package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatRoomItemResponse;
import com.audigo.domain.chat.dto.ChatRoomListQuery;
import com.audigo.domain.chat.dto.ChatRoomListResponse;
import com.audigo.domain.chat.repository.ChatMessageListRepository;
import com.audigo.domain.chat.repository.ChatRoomListRepository;
import com.audigo.domain.chat.repository.ChatRoomListRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomListService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_CHAT_NAME_LENGTH = 2;
    private static final int MAX_CHAT_NAME_LENGTH = 5;

    private final ChatRoomListRepository chatRoomListRepository;
    private final ChatMessageListRepository chatMessageListRepository;
    private final ChatPresenceQueryService chatPresenceQueryService;

    public ChatRoomListService(
            ChatRoomListRepository chatRoomListRepository,
            ChatMessageListRepository chatMessageListRepository,
            ChatPresenceQueryService chatPresenceQueryService
    ) {
        this.chatRoomListRepository = chatRoomListRepository;
        this.chatMessageListRepository = chatMessageListRepository;
        this.chatPresenceQueryService = chatPresenceQueryService;
    }

    @Transactional(readOnly = true)
    public ChatRoomListResponse list(Long userId, ChatRoomListQuery query) {
        String chatName = normalizeChatName(query.chatName());
        int pageSize = query.size() == null ? DEFAULT_SIZE : query.size();
        LocalDateTime cursorTime = query.cursorTime();

        List<ChatRoomListRow> fetchedRows = chatRoomListRepository.findPage(
                userId,
                chatName,
                cursorTime,
                query.cursorId(),
                pageSize + 1
        );
        boolean hasNext = fetchedRows.size() > pageSize;
        List<ChatRoomListRow> pageRows = hasNext
                ? fetchedRows.subList(0, pageSize)
                : fetchedRows;

        if (pageRows.isEmpty()) {
            return new ChatRoomListResponse(List.of(), 0, null, null, false);
        }

        List<Long> roomIds = pageRows.stream()
                .map(ChatRoomListRow::roomId)
                .toList();
        List<Long> opponentUserIds = pageRows.stream()
                .map(ChatRoomListRow::otherUserId)
                .distinct()
                .toList();
        Map<Long, String> latestMessages = chatMessageListRepository.findLatestMessages(roomIds);
        Map<Long, Long> unreadCounts = chatMessageListRepository.countUnreadMessages(roomIds, userId);
        Map<Long, Boolean> onlineStatuses = chatPresenceQueryService.findOnlineStatuses(opponentUserIds);

        List<ChatRoomItemResponse> rooms = pageRows.stream()
                .map(row -> new ChatRoomItemResponse(
                        row.roomId(),
                        row.chatName(),
                        row.status(),
                        onlineStatuses.get(row.otherUserId()),
                        latestMessages.getOrDefault(row.roomId(), ""),
                        row.lastMessageAt(),
                        row.profileImageUrl(),
                        Math.toIntExact(unreadCounts.getOrDefault(row.roomId(), 0L))
                ))
                .toList();

        ChatRoomListRow lastRow = hasNext ? pageRows.get(pageRows.size() - 1) : null;
        return new ChatRoomListResponse(
                rooms,
                rooms.size(),
                lastRow == null ? null : lastRow.lastMessageAt(),
                lastRow == null ? null : lastRow.roomId(),
                hasNext
        );
    }

    private String normalizeChatName(String chatName) {
        if (chatName == null) {
            return null;
        }

        String normalized = chatName.trim();
        if (normalized.length() < MIN_CHAT_NAME_LENGTH || normalized.length() > MAX_CHAT_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return normalized;
    }
}
