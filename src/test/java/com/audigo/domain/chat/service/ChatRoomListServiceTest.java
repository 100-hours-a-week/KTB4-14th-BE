package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatRoomListQuery;
import com.audigo.domain.chat.dto.ChatRoomListResponse;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatMessageListRepository;
import com.audigo.domain.chat.repository.ChatRoomListRepository;
import com.audigo.domain.chat.repository.ChatRoomListRow;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatRoomListServiceTest {

    @Mock
    private ChatRoomListRepository chatRoomListRepository;

    @Mock
    private ChatMessageListRepository chatMessageListRepository;

    @Mock
    private ChatPresenceRegistry chatPresenceRegistry;

    @InjectMocks
    private ChatRoomListService chatRoomListService;

    @Test
    void rejects_one_character_chat_name() {
        ChatRoomListQuery query = new ChatRoomListQuery("민", null, null, null);

        assertThatThrownBy(() -> chatRoomListService.list(7L, query))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST)
                );

        verifyNoInteractions(chatRoomListRepository, chatMessageListRepository, chatPresenceRegistry);
    }

    @Test
    void searches_with_two_character_chat_name() {
        ChatRoomListQuery query = new ChatRoomListQuery("민지", null, null, null);
        when(chatRoomListRepository.findPage(7L, "민지", null, null, 21)).thenReturn(List.of());

        ChatRoomListResponse response = chatRoomListService.list(7L, query);

        assertThat(response.rooms()).isEmpty();
        verify(chatRoomListRepository).findPage(7L, "민지", null, null, 21);
    }

    @Test
    void includes_opponent_online_state_from_the_presence_registry() {
        LocalDateTime lastMessageAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(chatRoomListRepository.findPage(7L, null, null, null, 21)).thenReturn(List.of(
                new ChatRoomListRow(54L, 2L, "민지", ChatRoomStatus.ACTIVE, lastMessageAt, null)
        ));
        when(chatMessageListRepository.findLatestMessages(List.of(54L)))
                .thenReturn(Map.of(54L, "안녕하세요"));
        when(chatMessageListRepository.countUnreadMessages(List.of(54L), 7L))
                .thenReturn(Map.of(54L, 1L));
        when(chatPresenceRegistry.isOnline(2L)).thenReturn(true);

        ChatRoomListResponse response = chatRoomListService.list(
                7L,
                new ChatRoomListQuery(null, null, null, null)
        );

        assertThat(response.rooms()).hasSize(1);
        assertThat(response.rooms().getFirst().isOnline()).isTrue();
        verify(chatPresenceRegistry).isOnline(2L);
    }
}
