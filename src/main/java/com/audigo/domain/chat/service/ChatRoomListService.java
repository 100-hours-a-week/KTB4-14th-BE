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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomListService {

    private static final Logger log = LoggerFactory.getLogger(ChatRoomListService.class);

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

        List<ChatRoomListRow> fetchedRows = executeStage(
                "room_page_query",
                pageSize,
                null,
                null,
                () -> chatRoomListRepository.findPage(
                        userId,
                        chatName,
                        cursorTime,
                        query.cursorId(),
                        pageSize + 1
                )
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
        Map<Long, String> latestMessages = executeStage(
                "latest_messages",
                pageSize,
                pageRows.size(),
                opponentUserIds.size(),
                () -> chatMessageListRepository.findLatestMessages(roomIds)
        );
        Map<Long, Long> unreadCounts = executeStage(
                "unread_counts",
                pageSize,
                pageRows.size(),
                opponentUserIds.size(),
                () -> chatMessageListRepository.countUnreadMessages(roomIds, userId)
        );
        Map<Long, Boolean> onlineStatuses = executeStage(
                "presence_lookup",
                pageSize,
                pageRows.size(),
                opponentUserIds.size(),
                () -> chatPresenceQueryService.findOnlineStatuses(opponentUserIds)
        );

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

    private <T> T executeStage(
            String stage,
            int pageSize,
            Integer roomCount,
            Integer uniqueUserCount,
            Supplier<T> action
    ) {
        long startedAt = System.nanoTime();
        try {
            T result = action.get();
            int measuredRoomCount = roomCount == null && result instanceof Collection<?> rows
                    ? rows.size()
                    : roomCount == null ? -1 : roomCount;
            log.debug(
                    "chat_room_list_stage_completed request_id={} stage={} page_size={} room_count={} "
                            + "unique_user_count={} duration_ms={}",
                    MDC.get("request_id"),
                    stage,
                    pageSize,
                    countValue(measuredRoomCount),
                    countValue(uniqueUserCount),
                    elapsedMillis(startedAt)
            );
            return result;
        } catch (RuntimeException exception) {
            String message = "chat_room_list_stage_failed request_id={} stage={} page_size={} room_count={} "
                    + "unique_user_count={} duration_ms={} exception_class={}";
            Object[] fields = {
                    MDC.get("request_id"),
                    stage,
                    pageSize,
                    countValue(roomCount),
                    countValue(uniqueUserCount),
                    elapsedMillis(startedAt),
                    exception.getClass().getSimpleName()
            };
            if ("presence_lookup".equals(stage)) {
                log.error(message, fields);
            } else {
                log.error(message, append(fields, exception));
            }
            throw exception;
        }
    }

    private static Object[] append(Object[] values, Object value) {
        Object[] result = new Object[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = value;
        return result;
    }

    private static String countValue(Integer count) {
        return count == null ? "unknown" : count.toString();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
