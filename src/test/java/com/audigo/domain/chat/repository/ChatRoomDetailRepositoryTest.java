package com.audigo.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatRoomDetailRepositoryTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private ChatRoomDetailRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatRoomDetailRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
    }

    @Test
    void queries_sent_messages_with_membership_scope_cursor_and_recipient_read_record() {
        LocalDateTime sentAt = LocalDateTime.of(2026, 9, 4, 8, 0, 20);
        when(query.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{101L, 1L, "안녕하세요", Timestamp.valueOf(sentAt), 1, 7L}
        ));

        List<ChatRoomDetailRepository.MessageDetailRow> rows = repository.findMessages(
                54L,
                1L,
                2L,
                sentAt,
                100L,
                21
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("chat_message.chat_room_id = :roomId")
                .contains("chat_message.status = 'SENT'")
                .contains("requester_member.deleted_at IS NULL")
                .contains("message_read.user_id = CASE")
                .contains("WHEN chat_message.sender_id = :userId THEN :opponentUserId")
                .contains("chat_message.sent_at < :cursorTime")
                .contains("chat_message.id < :cursorId")
                .contains("ORDER BY chat_message.sent_at DESC, chat_message.id DESC");
        verify(query).setParameter("opponentUserId", 2L);
        verify(query).setParameter("cursorTime", sentAt);
        verify(query).setParameter("cursorId", 100L);
        assertThat(rows).containsExactly(new ChatRoomDetailRepository.MessageDetailRow(
                101L,
                1L,
                "안녕하세요",
                sentAt,
                true,
                7L
        ));
    }

    @Test
    void returns_no_room_when_query_has_no_active_requester_membership() {
        when(query.getResultList()).thenReturn(List.of());

        assertThat(repository.findAccessibleRoom(54L, 1L)).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("requester_member.user_id = :userId")
                .contains("requester_member.deleted_at IS NULL")
                .contains("opponent_member.user_id <> :userId");
    }
}
