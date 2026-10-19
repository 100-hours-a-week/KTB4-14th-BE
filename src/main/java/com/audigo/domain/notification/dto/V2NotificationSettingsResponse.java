package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.NotificationSetting;

public record V2NotificationSettingsResponse(
        boolean matchSuccessEnabled,
        boolean chatEnabled,
        boolean travelCompleteEnabled,
        boolean notificationEnabled
) {
    public static V2NotificationSettingsResponse from(NotificationSetting setting) {
        return new V2NotificationSettingsResponse(
                setting.matchSuccessEnabled(),
                setting.chatEnabled(),
                setting.travelCompleteEnabled(),
                setting.notificationEnabled()
        );
    }
}
