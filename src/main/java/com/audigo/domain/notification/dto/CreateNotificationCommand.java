package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record CreateNotificationCommand(
        Long userId,
        NotificationType notificationType,
        String title,
        String content,
        NotificationTargetType targetType,
        Long targetId
) {
    private static final Logger log = LoggerFactory.getLogger(CreateNotificationCommand.class);

    public CreateNotificationCommand {
        validatePositive(userId, "user_id");
        validateRequired(notificationType, "notification_type");
        validateText(title, "title");
        validateText(content, "content");
        validateRequired(targetType, "target_type");
        validatePositive(targetId, "target_id");
    }

    private static void validateRequired(Object value, String field) {
        if (value == null) {
            log.warn("notification_command_invalid field={} reason=null", field);
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_REQUEST);
        }
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            log.warn("notification_command_invalid field={} reason=blank", field);
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_REQUEST);
        }
    }

    private static void validatePositive(Long value, String field) {
        if (value == null) {
            log.warn("notification_command_invalid field={} reason=null", field);
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_REQUEST);
        }
        if (value <= 0) {
            log.warn("notification_command_invalid field={} reason=not_positive value={}", field, value);
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_REQUEST);
        }
    }
}
