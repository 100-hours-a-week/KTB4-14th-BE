package com.audigo.domain.notification.dto;

public record UpdateNotificationSettingsRequest(
        Boolean matchSuccessEnabled,
        Boolean chatEnabled,
        Boolean travelBeforeEnabled,
        Boolean travelCompleteEnabled,
        Boolean notificationEnabled,
        Boolean travelReady,
        Boolean newChat,
        Boolean travelD1,
        Boolean travelFailed
) {
}
