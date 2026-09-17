package com.audigo.domain.notification.entity;

import com.audigo.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(nullable = false, length = 20)
    private String title;

    @Column(nullable = false, length = 30)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private NotificationTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected Notification() {
    }

    private Notification(
            User user,
            NotificationType notificationType,
            String title,
            String content,
            NotificationTargetType targetType,
            Long targetId
    ) {
        this.user = user;
        this.notificationType = notificationType;
        this.title = truncate(title, 20);
        this.content = truncate(content, 30);
        this.targetType = targetType;
        this.targetId = targetId;
        this.read = false;
    }

    public static Notification create(
            User user,
            NotificationType notificationType,
            String title,
            String content,
            NotificationTargetType targetType,
            Long targetId
    ) {
        return new Notification(user, notificationType, title, content, targetType, targetId);
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long id() {
        return id;
    }

    public NotificationType notificationType() {
        return notificationType;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public NotificationTargetType targetType() {
        return targetType;
    }

    public Long targetId() {
        return targetId;
    }

    public boolean read() {
        return read;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime readAt() {
        return readAt;
    }

    public void markRead() {
        if (this.read) {
            return;
        }
        this.read = true;
        this.readAt = LocalDateTime.now();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
