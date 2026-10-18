package com.audigo.domain.chat.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMessageSearchRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public List<Hit> search(Long roomId, String keyword, String fulltextCandidate,
                            LocalDateTime cursorTime, Long cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.sent_at
                FROM chat_messages m
                WHERE m.chat_room_id = :roomId AND m.status = 'SENT'
                  AND INSTR(m.content, :keyword) > 0
                """);
        if (fulltextCandidate != null) {
            sql.append(" AND MATCH(m.content) AGAINST (:fulltextCandidate IN BOOLEAN MODE)\n");
        }
        if (cursorTime != null) {
            sql.append(" AND (m.sent_at < :cursorTime OR (m.sent_at = :cursorTime AND m.id < :cursorId))\n");
        }
        sql.append(" ORDER BY m.sent_at DESC, m.id DESC");
        Query query = entityManager.createNativeQuery(sql.toString())
                .setParameter("roomId", roomId)
                .setParameter("keyword", keyword)
                .setMaxResults(limit);
        if (fulltextCandidate != null) query.setParameter("fulltextCandidate", fulltextCandidate);
        if (cursorTime != null) {
            query.setParameter("cursorTime", cursorTime);
            query.setParameter("cursorId", cursorId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new Hit(((Number) row[0]).longValue(), toLocalDateTime(row[1]))).toList();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return (LocalDateTime) value;
    }

    public record Hit(Long chatId, LocalDateTime sentAt) {
    }
}
