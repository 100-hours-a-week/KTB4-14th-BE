package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailResponse;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.MessageDetailRow;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository.RoomDetailRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatRoomDetailServiceTest {

    @Mock
    private ChatRoomDetailRepository chatRoomDetailRepository;

    @Mock
    private ChatPresenceQueryService chatPresenceQueryService;

    @InjectMocks
    private ChatRoomDetailService chatRoomDetailService;

    @Test
    void returns_ended_room_messages_in_display_order_without_changing_read_state() {
        LocalDateTime oldestSentAt = LocalDateTime.of(2026, 9, 4, 8, 0, 20);
        RoomDetailRow room = room(ChatRoomStatus.ENDED);
        when(chatRoomDetailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.of(room));
        when(chatRoomDetailRepository.findMessages(54L, 1L, 2L, null, null, 3)).thenReturn(List.of(
                new MessageDetailRow(102L, 2L, "상대 메시지", oldestSentAt.plusMinutes(1), true),
                new MessageDetailRow(101L, 1L, "내 메시지", oldestSentAt, false)
        ));
        when(chatPresenceQueryService.isOnline(2L)).thenReturn(true);

        ChatRoomDetailResponse response = chatRoomDetailService.getDetail(
                1L,
                ChatRoomDetailQuery.from("54", "2", null, null)
        );

        assertThat(response.chatRoomId()).isEqualTo(54L);
        assertThat(response.status()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(response.opponent().userId()).isEqualTo(2L);
        assertThat(response.opponent().chatName()).isEqualTo("상대 닉네임");
        assertThat(response.opponent().isOnline()).isTrue();
        assertThat(response.chats()).extracting(ChatRoomDetailResponse.Message::chatId)
                .containsExactly(101L, 102L);
        assertThat(response.chats()).extracting(ChatRoomDetailResponse.Message::isRead)
                .containsExactly(false, true);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.chatCursorTime()).isNull();
        assertThat(response.chatCursorId()).isNull();
        assertThat(response.hasNext()).isFalse();
        verify(chatRoomDetailRepository).findMessages(54L, 1L, 2L, null, null, 3);
        verify(chatPresenceQueryService).isOnline(2L);
    }

    @Test
    void returns_oldest_message_as_cursor_when_an_older_page_exists() {
        LocalDateTime cursorTime = LocalDateTime.of(2026, 9, 4, 8, 0, 20);
        LocalDateTime firstReturnedTime = cursorTime.plusMinutes(2);
        when(chatRoomDetailRepository.findAccessibleRoom(54L, 1L))
                .thenReturn(Optional.of(room(ChatRoomStatus.ACTIVE)));
        when(chatRoomDetailRepository.findMessages(54L, 1L, 2L, cursorTime, 101L, 3)).thenReturn(List.of(
                new MessageDetailRow(103L, 2L, "가장 최신", firstReturnedTime.plusMinutes(1), true),
                new MessageDetailRow(102L, 1L, "페이지의 가장 오래된 메시지", firstReturnedTime, false),
                new MessageDetailRow(101L, 2L, "다음 페이지에 속하는 메시지", cursorTime, true)
        ));

        ChatRoomDetailResponse response = chatRoomDetailService.getDetail(
                1L,
                ChatRoomDetailQuery.from("54", "2", "2026-09-04T08:00:20Z", "101")
        );

        assertThat(response.chats()).extracting(ChatRoomDetailResponse.Message::chatId)
                .containsExactly(102L, 103L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.chatCursorTime())
                .isEqualTo(firstReturnedTime.atOffset(ZoneOffset.UTC));
        assertThat(response.chatCursorId()).isEqualTo(102L);
    }

    @Test
    void returns_empty_messages_for_an_empty_room_page() {
        when(chatRoomDetailRepository.findAccessibleRoom(54L, 1L))
                .thenReturn(Optional.of(room(ChatRoomStatus.ACTIVE)));
        when(chatRoomDetailRepository.findMessages(54L, 1L, 2L, null, null, 21)).thenReturn(List.of());

        ChatRoomDetailResponse response = chatRoomDetailService.getDetail(
                1L,
                ChatRoomDetailQuery.from("54", null, null, null)
        );

        assertThat(response.chats()).isEmpty();
        assertThat(response.size()).isZero();
        assertThat(response.chatCursorTime()).isNull();
        assertThat(response.chatCursorId()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void returns_same_not_found_for_a_room_without_active_membership() {
        when(chatRoomDetailRepository.findAccessibleRoom(54L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomDetailService.getDetail(
                1L,
                ChatRoomDetailQuery.from("54", null, null, null)
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND)
                );

        verify(chatRoomDetailRepository, never()).findMessages(54L, 1L, 2L, null, null, 21);
        verify(chatPresenceQueryService, never()).isOnline(2L);
    }

    private RoomDetailRow room(ChatRoomStatus status) {
        return new RoomDetailRow(54L, status, 2L, "상대 닉네임", "https://example.com/profile.png");
    }
}
