package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;

public record CreateNotificationCommand(
        Long userId,
        NotificationType notificationType,
        String title,
        String content,
        NotificationTargetType targetType,
        Long targetId
) {
}
