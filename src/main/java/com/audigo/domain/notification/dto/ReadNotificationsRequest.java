package com.audigo.domain.notification.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ReadNotificationsRequest(
        @NotEmpty
        List<Long> notificationIds
) {
}
