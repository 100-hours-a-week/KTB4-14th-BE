package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.repository.ChatMessageSearchRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ES caller returns from this bean's transaction before making an HTTP request. */
@Service
public class ChatMessageSearchDatabase {
    private static final Pattern SEARCHABLE_RUN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private final ChatRoomDetailRepository detailRepository;
    private final ChatMessageSearchRepository searchRepository;

    public ChatMessageSearchDatabase(ChatRoomDetailRepository detailRepository, ChatMessageSearchRepository searchRepository) {
        this.detailRepository = detailRepository;
        this.searchRepository = searchRepository;
    }

    @Transactional(readOnly = true)
    public void requireRoom(Long roomId, Long userId) {
        detailRepository.findAccessibleRoom(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageSearchRepository.Hit> search(Long userId, ChatRoomDetailQuery query,
                                                       String keyword, boolean fulltext, int limit) {
        requireRoom(query.roomId(), userId);
        Matcher matcher = SEARCHABLE_RUN.matcher(keyword);
        String candidate = null;
        while (fulltext && matcher.find()) {
            if (candidate == null || matcher.group().length() > candidate.length()) candidate = matcher.group();
        }
        List<ChatMessageSearchRepository.Hit> fetched = searchRepository.search(
                query.roomId(), keyword, candidate, query.cursorTime(), query.cursorId(), limit);
        // Preserve the original stopword/zero-result fallback in the MySQL control.
        if (candidate != null && fetched.isEmpty()) {
            fetched = searchRepository.search(query.roomId(), keyword, null, query.cursorTime(), query.cursorId(), limit);
        }
        return fetched;
    }
}
