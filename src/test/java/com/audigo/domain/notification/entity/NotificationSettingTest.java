package com.audigo.domain.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.audigo.domain.user.entity.User;
import org.junit.jupiter.api.Test;

class NotificationSettingTest {

    @Test
    void updateV2DoesNotChangeLegacyTravelBeforeSetting() {
        NotificationSetting setting = NotificationSetting.createDefault(mock(User.class));
        setting.update(true, true, false, true, true);

        setting.updateV2(false, false, false, false);

        assertThat(setting.matchSuccessEnabled()).isFalse();
        assertThat(setting.chatEnabled()).isFalse();
        assertThat(setting.travelBeforeEnabled()).isFalse();
        assertThat(setting.travelCompleteEnabled()).isFalse();
        assertThat(setting.notificationEnabled()).isFalse();
    }
}
