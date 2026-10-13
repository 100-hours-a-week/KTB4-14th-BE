package com.audigo.domain.chat.entity;

import com.audigo.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "chat_messages",
        indexes = {
                @Index(name = "idx_chat_messages_room_status_sent_at", columnList = "chat_room_id,status,sent_at"),
                @Index(name = "uk_chat_messages_room_message_seq", columnList = "chat_room_id,message_seq", unique = true),
                @Index(name = "idx_chat_messages_sender_status_sent_at", columnList = "sender_id,status,sent_at")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_messages_room_sender_client_id",
                columnNames = {"chat_room_id", "sender_id", "client_message_id"}
        )
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "client_message_id", nullable = false, length = 36)
    private String clientMessageId;

    @Column(name = "message_seq", nullable = false)
    private long messageSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "ENUM('SENT','FAILED')")
    private ChatMessageStatus status;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    protected ChatMessage() {
    }

    private ChatMessage(
            ChatRoom chatRoom,
            User sender,
            String clientMessageId,
            String content,
            long messageSequence,
            LocalDateTime sentAt
    ) {
        this.chatRoom = Objects.requireNonNull(chatRoom, "채팅방은 필수입니다.");
        this.sender = Objects.requireNonNull(sender, "발신자는 필수입니다.");
        this.clientMessageId = Objects.requireNonNull(clientMessageId, "클라이언트 메시지 ID는 필수입니다.");
        this.content = Objects.requireNonNull(content, "메시지 내용은 필수입니다.");
        this.messageSequence = messageSequence;
        this.status = ChatMessageStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt, "전송 시각은 필수입니다.");
    }

    public static ChatMessage create(
            ChatRoom chatRoom,
            User sender,
            String clientMessageId,
            String content,
            long messageSequence,
            LocalDateTime sentAt
    ) {
        return new ChatMessage(chatRoom, sender, clientMessageId, content, messageSequence, sentAt);
    }

    public Long getId() {
        return id;
    }

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

    public User getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public long getMessageSequence() {
        return messageSequence;
    }

    public ChatMessageStatus getStatus() {
        return status;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
