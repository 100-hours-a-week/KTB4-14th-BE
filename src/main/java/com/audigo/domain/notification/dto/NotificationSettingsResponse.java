package com.audigo.domain.notification.dto;

import com.audigo.domain.notification.entity.NotificationSetting;

public record NotificationSettingsResponse(
        boolean travelReady,
        boolean newChat,
        boolean travelD1,
        boolean travelFailed
) {
    public static NotificationSettingsResponse from(NotificationSetting setting) {
        return new NotificationSettingsResponse(
                setting.travelReady(),
                setting.newChat(),
                setting.travelD1(),
                setting.travelFailed()
        );
    }
}
