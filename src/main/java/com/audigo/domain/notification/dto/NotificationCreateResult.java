package com.audigo.domain.notification.dto;

public record NotificationCreateResult(
        boolean created,
        NotificationResponse notification
) {
    public static NotificationCreateResult skipped() {
        return new NotificationCreateResult(false, null);
    }

    public static NotificationCreateResult created(NotificationResponse notification) {
        return new NotificationCreateResult(true, notification);
    }
}
