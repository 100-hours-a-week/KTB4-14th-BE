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
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ChatRoomListServiceTest {

    @Mock
    private ChatRoomListRepository chatRoomListRepository;

    @Mock
    private ChatMessageListRepository chatMessageListRepository;

    @Mock
    private ChatPresenceQueryService chatPresenceQueryService;

    @InjectMocks
    private ChatRoomListService chatRoomListService;

    @Test
    void rejects_one_character_chat_name() {
        ChatRoomListQuery query = new ChatRoomListQuery("민", null, null, null);

        assertThatThrownBy(() -> chatRoomListService.list(7L, query))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST)
                );

        verifyNoInteractions(chatRoomListRepository, chatMessageListRepository, chatPresenceQueryService);
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
    void includes_opponent_online_state_from_redis_batch_statuses() {
        LocalDateTime lastMessageAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(chatRoomListRepository.findPage(7L, null, null, null, 21)).thenReturn(List.of(
                new ChatRoomListRow(54L, 2L, "민지", ChatRoomStatus.ACTIVE, lastMessageAt, null),
                new ChatRoomListRow(55L, 3L, "서연", ChatRoomStatus.ACTIVE, lastMessageAt, null),
                new ChatRoomListRow(56L, 2L, "민지", ChatRoomStatus.ACTIVE, lastMessageAt, null)
        ));
        when(chatMessageListRepository.findLatestMessages(List.of(54L, 55L, 56L)))
                .thenReturn(Map.of(54L, "안녕하세요", 55L, "반가워요", 56L, "다시 만나요"));
        when(chatMessageListRepository.countUnreadMessages(List.of(54L, 55L, 56L), 7L))
                .thenReturn(Map.of(54L, 1L, 55L, 0L, 56L, 2L));
        when(chatPresenceQueryService.findOnlineStatuses(List.of(2L, 3L)))
                .thenReturn(Map.of(2L, true, 3L, false));

        ChatRoomListResponse response = chatRoomListService.list(
                7L,
                new ChatRoomListQuery(null, null, null, null)
        );

        assertThat(response.rooms()).hasSize(3);
        assertThat(response.rooms().getFirst().isOnline()).isTrue();
        assertThat(response.rooms().get(1).isOnline()).isFalse();
        assertThat(response.rooms().get(2).isOnline()).isTrue();
        verify(chatPresenceQueryService).findOnlineStatuses(List.of(2L, 3L));
    }

    @Test
    void logs_the_presence_stage_and_request_context_when_lookup_fails(CapturedOutput output) {
        LocalDateTime lastMessageAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(chatRoomListRepository.findPage(7L, null, null, null, 21)).thenReturn(List.of(
                new ChatRoomListRow(54L, 2L, "민지", ChatRoomStatus.ACTIVE, lastMessageAt, null),
                new ChatRoomListRow(55L, 2L, "민지", ChatRoomStatus.ACTIVE, lastMessageAt, null)
        ));
        when(chatMessageListRepository.findLatestMessages(List.of(54L, 55L)))
                .thenReturn(Map.of(54L, "안녕하세요", 55L, "다시 만나요"));
        when(chatMessageListRepository.countUnreadMessages(List.of(54L, 55L), 7L))
                .thenReturn(Map.of(54L, 1L, 55L, 2L));
        when(chatPresenceQueryService.findOnlineStatuses(List.of(2L)))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        MDC.put("request_id", "request-456");

        try {
            assertThatThrownBy(() -> chatRoomListService.list(
                    7L,
                    new ChatRoomListQuery(null, null, null, null)
            )).isInstanceOf(BusinessException.class);
        } finally {
            MDC.remove("request_id");
        }

        assertThat(output.getAll())
                .contains("chat_room_list_stage_failed")
                .contains("request_id=request-456")
                .contains("stage=presence_lookup")
                .contains("page_size=20")
                .contains("room_count=2")
                .contains("unique_user_count=1");
    }
}
