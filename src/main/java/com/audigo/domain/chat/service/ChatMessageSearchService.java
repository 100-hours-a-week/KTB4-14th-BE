package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatMessageContextResponse;
import com.audigo.domain.chat.dto.ChatMessageSearchResponse;
import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailResponse;
import com.audigo.domain.chat.repository.ChatMessageSearchRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.MessageDetailRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageSearchService {
    private static final int SEARCH_PAGE_SIZE = 50;
    private static final int CONTEXT_SIDE_SIZE = 10;
    private static final Pattern SEARCHABLE_RUN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private final ChatRoomDetailRepository detailRepository;
    private final ChatMessageSearchRepository searchRepository;

    public ChatMessageSearchService(ChatRoomDetailRepository detailRepository,
                                    ChatMessageSearchRepository searchRepository) {
        this.detailRepository = detailRepository;
        this.searchRepository = searchRepository;
    }

    @Transactional(readOnly = true)
    public ChatMessageSearchResponse search(Long userId, String rawRoomId, String rawKeyword,
                                            String rawCursorTime, String rawCursorId) {
        ChatRoomDetailQuery query = ChatRoomDetailQuery.from(rawRoomId, null, rawCursorTime, rawCursorId);
        String keyword = rawKeyword == null ? "" : rawKeyword.strip();
        if (keyword.codePointCount(0, keyword.length()) < 2 || keyword.codePointCount(0, keyword.length()) > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        requireRoom(query.roomId(), userId);
        Matcher matcher = SEARCHABLE_RUN.matcher(keyword);
        String candidate = null;
        while (matcher.find()) {
            if (candidate == null || matcher.group().length() > candidate.length()) candidate = matcher.group();
        }
        List<ChatMessageSearchRepository.Hit> fetched = searchRepository.search(
                query.roomId(), keyword, candidate, query.cursorTime(), query.cursorId(), SEARCH_PAGE_SIZE + 1);
        // MySQL ngram stopwords can discard a short candidate (for example "in").
        // Preserve literal-search correctness when the index finds no candidates.
        if (candidate != null && fetched.isEmpty()) {
            fetched = searchRepository.search(
                    query.roomId(), keyword, null, query.cursorTime(), query.cursorId(), SEARCH_PAGE_SIZE + 1);
        }
        boolean hasNext = fetched.size() > SEARCH_PAGE_SIZE;
        List<ChatMessageSearchRepository.Hit> page = hasNext ? fetched.subList(0, SEARCH_PAGE_SIZE) : fetched;
        ChatMessageSearchRepository.Hit oldest = page.isEmpty() ? null : page.getLast();
        return new ChatMessageSearchResponse(
                page.stream().map(hit -> new ChatMessageSearchResponse.Hit(
                        hit.chatId(), hit.sentAt().atOffset(ZoneOffset.UTC))).toList(),
                hasNext ? oldest.sentAt().atOffset(ZoneOffset.UTC) : null,
                hasNext ? oldest.chatId() : null,
                hasNext);
    }

    @Transactional(readOnly = true)
    public ChatMessageContextResponse context(Long userId, String rawRoomId, String rawChatId) {
        ChatRoomDetailQuery query = ChatRoomDetailQuery.from(rawRoomId, null, null, null);
        Long chatId = ChatRoomDetailQuery.from(rawChatId, null, null, null).roomId();
        var room = requireRoom(query.roomId(), userId);
        MessageDetailRow target = detailRepository.findMessage(room.roomId(), userId, room.opponentUserId(), chatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        List<MessageDetailRow> older = detailRepository.findMessages(
                room.roomId(), userId, room.opponentUserId(), target.sentAt(), target.chatId(), CONTEXT_SIDE_SIZE + 1);
        List<MessageDetailRow> newer = detailRepository.findNewerMessages(
                room.roomId(), userId, room.opponentUserId(), target.sentAt(), target.chatId(), CONTEXT_SIDE_SIZE + 1);
        boolean hasOlder = older.size() > CONTEXT_SIDE_SIZE;
        boolean hasNewer = newer.size() > CONTEXT_SIDE_SIZE;
        List<MessageDetailRow> chronological = new ArrayList<>(older.subList(0, Math.min(older.size(), CONTEXT_SIDE_SIZE)));
        Collections.reverse(chronological);
        chronological.add(target);
        chronological.addAll(newer.subList(0, Math.min(newer.size(), CONTEXT_SIDE_SIZE)));
        return new ChatMessageContextResponse(chronological.stream().map(message ->
                new ChatRoomDetailResponse.Message(message.chatId(), message.messageSequence(), message.senderId(),
                        message.content(), message.sentAt().atOffset(ZoneOffset.UTC), message.isRead())).toList(),
                hasOlder, hasNewer);
    }

    private ChatRoomDetailRepository.RoomDetailRow requireRoom(Long roomId, Long userId) {
        return detailRepository.findAccessibleRoom(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }
}
