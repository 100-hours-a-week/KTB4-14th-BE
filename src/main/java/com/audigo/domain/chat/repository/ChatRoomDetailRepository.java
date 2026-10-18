package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatRoomStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRoomDetailRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public Optional<RoomDetailRow> findAccessibleRoom(Long roomId, Long userId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT cr.id,
                               cr.status,
                               opponent_user.id,
                               opponent_user.nickname,
                               opponent_user.profile_image_url
                        FROM chat_rooms cr
                        JOIN chat_room_members requester_member
                          ON requester_member.chat_room_id = cr.id
                         AND requester_member.user_id = :userId
                         AND requester_member.deleted_at IS NULL
                        JOIN chat_room_members opponent_member
                          ON opponent_member.chat_room_id = cr.id
                         AND opponent_member.user_id <> :userId
                        JOIN users opponent_user
                          ON opponent_user.id = opponent_member.user_id
                        WHERE cr.id = :roomId
                        """)
                .setParameter("roomId", roomId)
                .setParameter("userId", userId)
                .setMaxResults(2)
                .getResultList();

        if (rows.size() != 1) {
            return Optional.empty();
        }

        Object[] row = rows.getFirst();
        return Optional.of(new RoomDetailRow(
                ((Number) row[0]).longValue(),
                ChatRoomStatus.valueOf(row[1].toString()),
                ((Number) row[2]).longValue(),
                (String) row[3],
                (String) row[4]
        ));
    }

    public List<MessageDetailRow> findMessages(
            Long roomId,
            Long userId,
            Long opponentUserId,
            LocalDateTime cursorTime,
            Long cursorId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT chat_message.id,
                       chat_message.sender_id,
                       chat_message.content,
                       chat_message.sent_at,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM chat_message_reads message_read
                           WHERE message_read.message_id = chat_message.id
                             AND message_read.user_id = CASE
                                 WHEN chat_message.sender_id = :userId THEN :opponentUserId
                                 ELSE :userId
                             END
                       ) THEN 1 ELSE 0 END AS is_read,
                       chat_message.message_seq
                FROM chat_messages chat_message
                WHERE chat_message.chat_room_id = :roomId
                  AND chat_message.status = 'SENT'
                  AND EXISTS (
                      SELECT 1
                      FROM chat_room_members requester_member
                      WHERE requester_member.chat_room_id = chat_message.chat_room_id
                        AND requester_member.user_id = :userId
                        AND requester_member.deleted_at IS NULL
                  )
                """);

        if (cursorTime != null) {
            sql.append("""
                    AND (
                        chat_message.sent_at < :cursorTime
                        OR (chat_message.sent_at = :cursorTime AND chat_message.id < :cursorId)
                    )
                    """);
        }
        sql.append(" ORDER BY chat_message.sent_at DESC, chat_message.id DESC");

        Query query = entityManager.createNativeQuery(sql.toString())
                .setParameter("roomId", roomId)
                .setParameter("userId", userId)
                .setParameter("opponentUserId", opponentUserId)
                .setMaxResults(limit);
        if (cursorTime != null) {
            query.setParameter("cursorTime", cursorTime);
            query.setParameter("cursorId", cursorId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::toMessageDetailRow).toList();
    }

    public Optional<MessageDetailRow> findMessage(Long roomId, Long userId, Long opponentUserId, Long chatId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT m.id, m.sender_id, m.content, m.sent_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM chat_message_reads r
                           WHERE r.message_id = m.id
                             AND r.user_id = CASE WHEN m.sender_id = :userId THEN :opponentUserId ELSE :userId END
                       ) THEN 1 ELSE 0 END, m.message_seq
                FROM chat_messages m
                WHERE m.id = :chatId AND m.chat_room_id = :roomId AND m.status = 'SENT'
                """)
                .setParameter("roomId", roomId)
                .setParameter("userId", userId)
                .setParameter("opponentUserId", opponentUserId)
                .setParameter("chatId", chatId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst().map(this::toMessageDetailRow);
    }

    public List<MessageDetailRow> findNewerMessages(
            Long roomId, Long userId, Long opponentUserId, LocalDateTime anchorTime, Long anchorId, int limit
    ) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT m.id, m.sender_id, m.content, m.sent_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM chat_message_reads r
                           WHERE r.message_id = m.id
                             AND r.user_id = CASE WHEN m.sender_id = :userId THEN :opponentUserId ELSE :userId END
                       ) THEN 1 ELSE 0 END, m.message_seq
                FROM chat_messages m
                WHERE m.chat_room_id = :roomId AND m.status = 'SENT'
                  AND (m.sent_at > :anchorTime OR (m.sent_at = :anchorTime AND m.id > :anchorId))
                ORDER BY m.sent_at ASC, m.id ASC
                """)
                .setParameter("roomId", roomId)
                .setParameter("userId", userId)
                .setParameter("opponentUserId", opponentUserId)
                .setParameter("anchorTime", anchorTime)
                .setParameter("anchorId", anchorId)
                .setMaxResults(limit)
                .getResultList();
        return rows.stream().map(this::toMessageDetailRow).toList();
    }

    private MessageDetailRow toMessageDetailRow(Object[] row) {
        return new MessageDetailRow(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                (String) row[2],
                toLocalDateTime(row[3]),
                ((Number) row[4]).intValue() == 1,
                ((Number) row[5]).longValue()
        );
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        throw new IllegalStateException("Unsupported chat message timestamp value: " + value);
    }

    public record RoomDetailRow(
            Long roomId,
            ChatRoomStatus status,
            Long opponentUserId,
            String opponentChatName,
            String opponentProfileImageUrl
    ) {
    }

    public record MessageDetailRow(
            Long chatId,
            Long senderId,
            String content,
            LocalDateTime sentAt,
            boolean isRead,
            long messageSequence
    ) {
        public MessageDetailRow(
                Long chatId,
                Long senderId,
                String content,
                LocalDateTime sentAt,
                boolean isRead
        ) {
            this(chatId, senderId, content, sentAt, isRead, chatId);
        }
    }
}
