package com.audigo.domain.matching.entity;

import com.audigo.domain.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "chat_rooms",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_rooms_match_connection",
                columnNames = "match_connection_id"
        )
)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_connection_id", nullable = false, unique = true)
    private MatchConnection matchConnection;

    @Column(name = "chat_name", nullable = false, length = 20)
    private String chatName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatRoomStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatRoomMember> members = new ArrayList<>();

    protected ChatRoom() {
    }

    private ChatRoom(MatchConnection matchConnection, String chatName) {
        this.matchConnection = Objects.requireNonNull(matchConnection, "매칭 연결은 필수입니다.");
        this.chatName = normalizeChatName(chatName);
        this.status = ChatRoomStatus.ACTIVE;
    }

    public static ChatRoom create(MatchConnection matchConnection, String chatName, User requester, User targetUser) {
        ChatRoom chatRoom = new ChatRoom(matchConnection, chatName);
        chatRoom.addMember(requester);
        chatRoom.addMember(targetUser);
        return chatRoom;
    }

    private void addMember(User user) {
        members.add(ChatRoomMember.create(this, user));
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

    public MatchConnection getMatchConnection() {
        return matchConnection;
    }

    public String getChatName() {
        return chatName;
    }

    public ChatRoomStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<ChatRoomMember> getMembers() {
        return Collections.unmodifiableList(members);
    }

    private String normalizeChatName(String value) {
        if (value == null || value.isBlank()) {
            return "매칭 채팅방";
        }
        String normalized = value.trim();
        return normalized.length() <= 20 ? normalized : normalized.substring(0, 20);
    }
}
