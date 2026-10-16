package com.audigo.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "chat_rooms",
        indexes = @Index(name = "idx_chat_rooms_last_message", columnList = "last_message_at,id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_rooms_match_connection",
                columnNames = "match_connection_id"
        )
)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "match_connection_id", nullable = false)
    private Long matchConnectionId;

    @Column(name = "chat_name", nullable = false, length = 20)
    private String chatName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "ENUM('ACTIVE','ENDED')")
    private ChatRoomStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_message_sequence", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long lastMessageSequence;

    protected ChatRoom() {
    }

    public static ChatRoom createForMatchConnection(Long matchConnectionId, String chatName) {
        ChatRoom room = new ChatRoom();
        room.matchConnectionId = Objects.requireNonNull(matchConnectionId, "매칭 연결은 필수입니다.");
        room.chatName = normalizeChatName(chatName);
        room.status = ChatRoomStatus.ACTIVE;
        return room;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public ChatRoomStatus getStatus() {
        return status;
    }

    public Long getMatchConnectionId() {
        return matchConnectionId;
    }

    public String getChatName() {
        return chatName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public long recordMessage(LocalDateTime sentAt) {
        lastMessageSequence += 1;
        lastMessageAt = sentAt;
        return lastMessageSequence;
    }

    private static String normalizeChatName(String value) {
        if (value == null || value.isBlank()) {
            return "매칭 채팅방";
        }
        String normalized = value.trim();
        return normalized.length() <= 20 ? normalized : normalized.substring(0, 20);
    }

    public void end() {
        status = ChatRoomStatus.ENDED;
    }
}
