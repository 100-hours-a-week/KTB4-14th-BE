package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String body,
        @JsonProperty("is_read")
        boolean isRead,
        LocalDateTime createdAt,
        Long travelPlanId
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.id(),
                notification.type(),
                notification.title(),
                notification.body(),
                notification.read(),
                notification.createdAt(),
                notification.travelPlanId()
        );
    }
}
