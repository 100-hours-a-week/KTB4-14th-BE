package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatMessageRead;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageReadRepository extends JpaRepository<ChatMessageRead, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO chat_message_reads (message_id, user_id, read_at)
            SELECT source_message.id, :userId, :readAt
            FROM chat_messages source_message
            WHERE source_message.chat_room_id = :roomId
              AND source_message.status = 'SENT'
              AND source_message.sender_id <> :userId
              AND source_message.message_seq <= :maxMessageSequence
            """, nativeQuery = true)
    int insertUnreadMessagesThrough(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("maxMessageSequence") long maxMessageSequence,
            @Param("readAt") LocalDateTime readAt
    );

    @Query("""
            select max(messageRead.readAt)
            from ChatMessageRead messageRead
            where messageRead.user.id = :userId
              and messageRead.message.chatRoom.id = :roomId
              and messageRead.message.status = :status
              and messageRead.message.sender.id <> :userId
              and messageRead.message.messageSequence <= :maxMessageSequence
            """)
    Optional<LocalDateTime> findLatestReadAtThrough(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("maxMessageSequence") long maxMessageSequence,
            @Param("status") ChatMessageStatus status
    );
}
