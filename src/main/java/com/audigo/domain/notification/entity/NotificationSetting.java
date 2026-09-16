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
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_settings")
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_setting_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "travel_ready", nullable = false)
    private boolean travelReady = true;

    @Column(name = "new_chat", nullable = false)
    private boolean newChat = true;

    @Column(name = "travel_d1", nullable = false)
    private boolean travelD1 = true;

    @Column(name = "travel_failed", nullable = false)
    private boolean travelFailed = true;

    protected NotificationSetting() {
    }

    private NotificationSetting(User user) {
        this.user = user;
    }

    public static NotificationSetting createDefault(User user) {
        return new NotificationSetting(user);
    }

    public boolean travelReady() {
        return travelReady;
    }

    public boolean newChat() {
        return newChat;
    }

    public boolean travelD1() {
        return travelD1;
    }

    public boolean travelFailed() {
        return travelFailed;
    }

    public void update(boolean travelReady, boolean newChat, boolean travelD1, boolean travelFailed) {
        this.travelReady = travelReady;
        this.newChat = newChat;
        this.travelD1 = travelD1;
        this.travelFailed = travelFailed;
    }
}
