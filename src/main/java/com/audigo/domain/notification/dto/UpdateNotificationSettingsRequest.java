package com.audigo.domain.notification.dto;

public record UpdateNotificationSettingsRequest(
        boolean travelReady,
        boolean newChat,
        boolean travelD1,
        boolean travelFailed
) {
}
