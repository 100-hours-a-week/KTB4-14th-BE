package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long notificationId,
        NotificationType notificationType,
        String title,
        String content,
        String body,
        NotificationTargetType targetType,
        Long targetId,
        @JsonProperty("is_read")
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification) {
        String content = notification.content();
        return new NotificationResponse(
                notification.id(),
                notification.notificationType(),
                notification.title(),
                content,
                content,
                notification.targetType(),
                notification.targetId(),
                notification.read(),
                notification.createdAt(),
                notification.readAt()
        );
    }
}
