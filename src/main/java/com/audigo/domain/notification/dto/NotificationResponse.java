package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationResponse(
        @JsonProperty("notification_id")
        Long notificationId,
        @JsonProperty("type")
        NotificationType type,
        String title,
        String content,
        String body,
        @JsonProperty("target_type")
        NotificationTargetType targetType,
        @JsonProperty("target_id")
        Long targetId,
        @JsonProperty("travel_plan_id")
        Long travelPlanId,
        @JsonProperty("is_read")
        boolean isRead,
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @JsonProperty("read_at")
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification) {
        String content = notification.content();
        Long travelPlanId = notification.targetType() == NotificationTargetType.TRAVEL_PLAN
                ? notification.targetId()
                : null;
        return new NotificationResponse(
                notification.id(),
                notification.notificationType(),
                notification.title(),
                content,
                content,
                notification.targetType(),
                notification.targetId(),
                travelPlanId,
                notification.read(),
                notification.createdAt(),
                notification.readAt()
        );
    }
}
