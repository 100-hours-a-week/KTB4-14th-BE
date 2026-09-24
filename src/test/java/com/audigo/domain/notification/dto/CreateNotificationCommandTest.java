package com.audigo.domain.notification.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

class CreateNotificationCommandTest {

    @Test
    void 필수값이_없으면_invalid_notification_request를_던진다() {
        assertThatThrownBy(() -> new CreateNotificationCommand(
                null,
                NotificationType.TRAVEL_COMPLETE,
                "여행 추천 완료",
                "여행 일정이 완성됐어요.",
                NotificationTargetType.TRAVEL_PLAN,
                1L
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_NOTIFICATION_REQUEST);
    }

    @Test
    void 제목과_내용은_공백일_수_없다() {
        assertThatThrownBy(() -> new CreateNotificationCommand(
                1L,
                NotificationType.TRAVEL_COMPLETE,
                " ",
                "여행 일정이 완성됐어요.",
                NotificationTargetType.TRAVEL_PLAN,
                1L
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_NOTIFICATION_REQUEST);

        assertThatThrownBy(() -> new CreateNotificationCommand(
                1L,
                NotificationType.TRAVEL_COMPLETE,
                "여행 추천 완료",
                "",
                NotificationTargetType.TRAVEL_PLAN,
                1L
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_NOTIFICATION_REQUEST);
    }

    @Test
    void 식별자는_양수여야_한다() {
        assertThatThrownBy(() -> new CreateNotificationCommand(
                1L,
                NotificationType.TRAVEL_COMPLETE,
                "여행 추천 완료",
                "여행 일정이 완성됐어요.",
                NotificationTargetType.TRAVEL_PLAN,
                0L
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_NOTIFICATION_REQUEST);
    }
}
