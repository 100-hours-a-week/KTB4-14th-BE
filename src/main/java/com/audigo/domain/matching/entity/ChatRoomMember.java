package com.audigo.domain.matching.entity;

import com.audigo.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "chat_room_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_room_members_room_user",
                columnNames = {"chat_room_id", "user_id"}
        )
)
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ChatRoomMember() {
    }

    private ChatRoomMember(ChatRoom chatRoom, User user) {
        this.chatRoom = Objects.requireNonNull(chatRoom, "채팅방은 필수입니다.");
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
    }

    static ChatRoomMember create(ChatRoom chatRoom, User user) {
        return new ChatRoomMember(chatRoom, user);
    }

    @PrePersist
    void prePersist() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
