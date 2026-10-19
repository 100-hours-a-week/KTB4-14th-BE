package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatMessageContextResponse;
import com.audigo.domain.chat.dto.ChatMessageSearchResponse;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatMessageSearchRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.MessageDetailRow;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.RoomDetailRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMessageSearchServiceTest {
    @Mock private ChatRoomDetailRepository detailRepository;
    @Mock private ChatMessageSearchRepository searchRepository;
    private ChatMessageSearchService service;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final RoomDetailRow ROOM = new RoomDetailRow(54L, ChatRoomStatus.ACTIVE, 2L, "상대", null);

    @BeforeEach
    void setUp() {
        service = new ChatMessageSearchService(detailRepository, searchRepository);
    }

    @Test
    void rejects_short_keyword_and_missing_cursor_pair() {
        assertThatThrownBy(() -> service.search(1L, "54", "가", null, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.search(1L, "54", "검색", "2026-10-01T09:00:00Z", null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(searchRepository, never()).search(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void prevents_search_and_context_for_a_nonmember() {
        when(detailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.search(1L, "54", "검색", null, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
        assertThatThrownBy(() -> service.context(1L, "54", "101"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
        verify(searchRepository, never()).search(any(), any(), any(), any(), any(), anyInt());
        verify(detailRepository, never()).findMessage(any(), any(), any(), any());
    }

    @Test
    void returns_newest_first_and_uses_the_last_visible_hit_as_cursor() {
        when(detailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.of(ROOM));
        List<ChatMessageSearchRepository.Hit> fetched = IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new ChatMessageSearchRepository.Hit(152L - index, TIME.minusMinutes(index)))
                .toList();
        when(searchRepository.search(54L, "월정리", "월정리", null, null, 51)).thenReturn(fetched);

        ChatMessageSearchResponse result = service.search(1L, "54", "월정리", null, null);

        assertThat(result.matches()).hasSize(50);
        assertThat(result.matches().getFirst().chatId()).isEqualTo(151L);
        assertThat(result.matches().getLast().chatId()).isEqualTo(102L);
        assertThat(result.cursorId()).isEqualTo(102L);
        assertThat(result.cursorTime()).isEqualTo(TIME.minusMinutes(50).atOffset(ZoneOffset.UTC));
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void falls_back_to_literal_search_when_ngram_stopword_has_no_matches() {
        when(detailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.of(ROOM));
        when(searchRepository.search(54L, "in", "in", null, null, 51)).thenReturn(List.of());
        when(searchRepository.search(54L, "in", null, null, null, 51)).thenReturn(List.of(
                new ChatMessageSearchRepository.Hit(100L, TIME)));

        ChatMessageSearchResponse result = service.search(1L, "54", "in", null, null);

        assertThat(result.matches()).extracting(hit -> hit.chatId()).containsExactly(100L);
        verify(searchRepository).search(54L, "in", null, null, null, 51);
    }

    @Test
    void returns_context_in_chronological_order_with_recipient_read_flags() {
        when(detailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.of(ROOM));
        MessageDetailRow target = new MessageDetailRow(101L, 1L, "월정리", TIME, false, 8L);
        when(detailRepository.findMessage(54L, 1L, 2L, 101L)).thenReturn(Optional.of(target));
        when(detailRepository.findMessages(54L, 1L, 2L, TIME, 101L, 11)).thenReturn(List.of(
                new MessageDetailRow(100L, 2L, "바로 전", TIME.minusMinutes(1), true, 7L),
                new MessageDetailRow(99L, 1L, "그 전", TIME.minusMinutes(2), false, 6L)));
        when(detailRepository.findNewerMessages(54L, 1L, 2L, TIME, 101L, 11)).thenReturn(List.of(
                new MessageDetailRow(102L, 2L, "바로 후", TIME.plusMinutes(1), true, 9L)));

        ChatMessageContextResponse result = service.context(1L, "54", "101");

        assertThat(result.chats()).extracting(message -> message.chatId()).containsExactly(99L, 100L, 101L, 102L);
        assertThat(result.chats()).extracting(message -> message.isRead()).containsExactly(false, true, false, true);
        assertThat(result.hasOlder()).isFalse();
        assertThat(result.hasNewer()).isFalse();
    }

    @Test
    void returns_not_found_for_a_message_outside_this_room() {
        when(detailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.of(ROOM));
        when(detailRepository.findMessage(54L, 1L, 2L, 999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.context(1L, "54", "999"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
    }
}
