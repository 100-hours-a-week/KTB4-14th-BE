package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatRoomStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRoomListRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<ChatRoomListRow> findPage(
            Long userId,
            LocalDateTime cursorTime,
            Long cursorId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT cr.id,
                       other_member.user_id,
                       other_user.nickname,
                       cr.status,
                       cr.last_message_at,
                       other_user.profile_image_url
                FROM chat_rooms cr
                JOIN chat_room_members other_member
                  ON other_member.chat_room_id = cr.id
                 AND other_member.user_id <> :userId
                JOIN users other_user
                  ON other_user.id = other_member.user_id
                WHERE cr.last_message_at IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM chat_room_members current_member
                      WHERE current_member.chat_room_id = cr.id
                        AND current_member.user_id = :userId
                        AND current_member.deleted_at IS NULL
                  )
                """);

        if (cursorTime != null) {
            sql.append("""
                    AND (
                        cr.last_message_at < :cursorTime
                        OR (cr.last_message_at = :cursorTime AND cr.id > :cursorId)
                    )
                    """);
        }

        sql.append(" ORDER BY cr.last_message_at DESC, cr.id ASC");

        Query query = entityManager.createNativeQuery(sql.toString())
                .setParameter("userId", userId);
        if (cursorTime != null) {
            query.setParameter("cursorTime", cursorTime);
            query.setParameter("cursorId", cursorId);
        }
        query.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        return results.stream().map(this::toRow).toList();
    }

    private ChatRoomListRow toRow(Object[] columns) {
        return new ChatRoomListRow(
                ((Number) columns[0]).longValue(),
                ((Number) columns[1]).longValue(),
                (String) columns[2],
                ChatRoomStatus.valueOf(columns[3].toString()),
                toLocalDateTime(columns[4]),
                (String) columns[5]
        );
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        throw new IllegalStateException("Unsupported chat room timestamp value: " + value);
    }
}
