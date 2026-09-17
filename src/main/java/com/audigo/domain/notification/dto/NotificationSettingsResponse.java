package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.NotificationSetting;

public record NotificationSettingsResponse(
        boolean matchSuccessEnabled,
        boolean chatEnabled,
        boolean travelBeforeEnabled,
        boolean travelCompleteEnabled,
        boolean notificationEnabled,
        boolean travelReady,
        boolean newChat,
        boolean travelD1,
        boolean travelFailed
) {
    public static NotificationSettingsResponse from(NotificationSetting setting) {
        return new NotificationSettingsResponse(
                setting.matchSuccessEnabled(),
                setting.chatEnabled(),
                setting.travelBeforeEnabled(),
                setting.travelCompleteEnabled(),
                setting.notificationEnabled(),
                setting.travelCompleteEnabled(),
                setting.chatEnabled(),
                setting.travelBeforeEnabled(),
                setting.travelCompleteEnabled()
        );
    }
}
