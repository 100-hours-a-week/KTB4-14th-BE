package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByChatRoom_IdAndSender_IdAndClientMessageId(
            Long roomId,
            Long senderId,
            String clientMessageId
    );

    Optional<ChatMessage> findByIdAndChatRoom_IdAndStatus(
            Long messageId,
            Long roomId,
            ChatMessageStatus status
    );

    @Query("""
            select count(message.id)
            from ChatMessage message
            where message.chatRoom.id = :roomId
              and message.status = :status
              and message.sender.id <> :userId
              and not exists (
                  select messageRead.id
                  from ChatMessageRead messageRead
                  where messageRead.message = message
                    and messageRead.user.id = :userId
              )
            """)
    long countUnreadMessages(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("status") ChatMessageStatus status
    );

    @Query("""
            select count(message.id)
            from ChatMessage message
            where message.sender.id = :senderId
              and message.status = :status
              and message.sentAt > :windowStart
            """)
    long countSentBySenderSince(
            @Param("senderId") Long senderId,
            @Param("status") ChatMessageStatus status,
            @Param("windowStart") LocalDateTime windowStart
    );

    @Query("""
            select min(message.sentAt)
            from ChatMessage message
            where message.sender.id = :senderId
              and message.status = :status
              and message.sentAt > :windowStart
            """)
    Optional<LocalDateTime> findOldestSentAtBySenderSince(
            @Param("senderId") Long senderId,
            @Param("status") ChatMessageStatus status,
            @Param("windowStart") LocalDateTime windowStart
    );

    List<ChatMessage> findByChatRoom_IdAndStatusAndMessageSequenceGreaterThanOrderByMessageSequenceAsc(
            Long roomId,
            ChatMessageStatus status,
            long messageSequence,
            Pageable pageable
    );
}
