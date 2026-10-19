package com.audigo.domain.notification.dto;

public record V2UpdateNotificationSettingsRequest(
        Boolean matchSuccessEnabled,
        Boolean chatEnabled,
        Boolean travelCompleteEnabled,
        Boolean notificationEnabled
) {
}
