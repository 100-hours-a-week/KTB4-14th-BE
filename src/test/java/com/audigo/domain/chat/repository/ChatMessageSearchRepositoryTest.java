package com.audigo.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
class ChatMessageSearchRepositoryTest {
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    private ChatMessageSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatMessageSearchRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
    }

    @Test
    void limits_to_sent_messages_in_room_and_uses_fulltext_and_compound_cursor() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{101L, Timestamp.valueOf(time)}));

        var hits = repository.search(54L, "월정리", "월정리", time, 102L, 51);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("m.chat_room_id = :roomId", "m.status = 'SENT'",
                "INSTR(m.content, :keyword) > 0", "MATCH(m.content) AGAINST (:fulltextCandidate IN BOOLEAN MODE)",
                "m.sent_at < :cursorTime", "m.id < :cursorId", "ORDER BY m.sent_at DESC, m.id DESC");
        verify(query).setParameter("fulltextCandidate", "월정리");
        verify(query).setParameter("cursorId", 102L);
        assertThat(hits).containsExactly(new ChatMessageSearchRepository.Hit(101L, time));
    }

    @Test
    void searches_punctuation_without_a_fulltext_candidate() {
        when(query.getResultList()).thenReturn(List.of());
        repository.search(54L, "!!", null, null, null, 51);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).doesNotContain("MATCH(", ":cursorTime");
        verify(query, never()).setParameter(org.mockito.ArgumentMatchers.eq("fulltextCandidate"), any());
    }
}
