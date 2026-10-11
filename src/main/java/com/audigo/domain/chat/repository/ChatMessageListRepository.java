package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatMessageStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMessageListRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public Map<Long, String> findLatestMessages(List<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }

        String jpql = """
                SELECT message.chatRoom.id, message.content
                FROM ChatMessage message
                WHERE message.chatRoom.id IN :roomIds
                  AND message.status = :sentStatus
                  AND NOT EXISTS (
                      SELECT newerMessage.id
                      FROM ChatMessage newerMessage
                      WHERE newerMessage.chatRoom.id = message.chatRoom.id
                        AND newerMessage.status = :sentStatus
                        AND (
                            newerMessage.sentAt > message.sentAt
                            OR (
                                newerMessage.sentAt = message.sentAt
                                AND newerMessage.id > message.id
                            )
                        )
                  )
                """;

        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("roomIds", roomIds)
                .setParameter("sentStatus", ChatMessageStatus.SENT);

        Map<Long, String> latestMessages = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            Long roomId = ((Number) row[0]).longValue();
            String content = (String) row[1];
            latestMessages.put(roomId, content);
        }
        return latestMessages;
    }

    public Map<Long, Long> countUnreadMessages(List<Long> roomIds, Long userId) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }

        String jpql = """
                SELECT message.chatRoom.id, COUNT(message.id)
                FROM ChatMessage message
                WHERE message.chatRoom.id IN :roomIds
                  AND message.status = :sentStatus
                  AND message.sender.id <> :userId
                  AND NOT EXISTS (
                      SELECT messageRead.id
                      FROM ChatMessageRead messageRead
                      WHERE messageRead.message = message
                        AND messageRead.user.id = :userId
                  )
                GROUP BY message.chatRoom.id
                """;

        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("roomIds", roomIds)
                .setParameter("sentStatus", ChatMessageStatus.SENT)
                .setParameter("userId", userId);

        Map<Long, Long> unreadCounts = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            Long roomId = ((Number) row[0]).longValue();
            Long unreadCount = ((Number) row[1]).longValue();
            unreadCounts.put(roomId, unreadCount);
        }
        return unreadCounts;
    }
}
