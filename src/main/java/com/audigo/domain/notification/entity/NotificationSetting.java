package com.audigo.domain.notification.entity;

import com.audigo.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_settings")
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "match_success_enabled", nullable = false)
    private boolean matchSuccessEnabled = true;

    @Column(name = "chat_enabled", nullable = false)
    private boolean chatEnabled = true;

    @Column(name = "travel_before_enabled", nullable = false)
    private boolean travelBeforeEnabled = true;

    @Column(name = "travel_complete_enabled", nullable = false)
    private boolean travelCompleteEnabled = true;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NotificationSetting() {
    }

    private NotificationSetting(User user) {
        this.user = user;
    }

    public static NotificationSetting createDefault(User user) {
        return new NotificationSetting(user);
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean matchSuccessEnabled() {
        return matchSuccessEnabled;
    }

    public boolean chatEnabled() {
        return chatEnabled;
    }

    public boolean travelBeforeEnabled() {
        return travelBeforeEnabled;
    }

    public boolean travelCompleteEnabled() {
        return travelCompleteEnabled;
    }

    public boolean notificationEnabled() {
        return notificationEnabled;
    }

    public boolean enabledFor(NotificationType notificationType) {
        if (!notificationEnabled) {
            return false;
        }
        return switch (notificationType) {
            case MATCH_SUCCESS -> matchSuccessEnabled;
            case NEW_MESSAGE -> chatEnabled;
            case TRAVEL_BEFORE -> travelBeforeEnabled;
            case TRAVEL_COMPLETE, TRAVEL_FAILED -> travelCompleteEnabled;
        };
    }

    public void update(
            boolean matchSuccessEnabled,
            boolean chatEnabled,
            boolean travelBeforeEnabled,
            boolean travelCompleteEnabled,
            boolean notificationEnabled
    ) {
        this.matchSuccessEnabled = matchSuccessEnabled;
        this.chatEnabled = chatEnabled;
        this.travelBeforeEnabled = travelBeforeEnabled;
        this.travelCompleteEnabled = travelCompleteEnabled;
        this.notificationEnabled = notificationEnabled;
    }
}
