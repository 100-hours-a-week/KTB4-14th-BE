package com.audigo.domain.notification.controller;

import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.dto.NotificationSettingsResponse;
import com.audigo.domain.notification.dto.UnreadCountResponse;
import com.audigo.domain.notification.dto.UpdateNotificationSettingsRequest;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final CurrentUser currentUser;
    private final NotificationService notificationService;

    public NotificationController(CurrentUser currentUser, NotificationService notificationService) {
        this.currentUser = currentUser;
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    public ApiResponse<List<NotificationResponse>> list() {
        return ApiResponse.of("notifications_found", notificationService.list(currentUser.id()));
    }

    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount() {
        return ApiResponse.of("notification_unread_count_found", notificationService.unreadCount(currentUser.id()));
    }

    @PatchMapping("/api/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long notificationId) {
        notificationService.markRead(currentUser.id(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/notifications/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead(currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/notification-settings")
    public ApiResponse<NotificationSettingsResponse> getSettings() {
        return ApiResponse.of("notification_settings_found", notificationService.getSettings(currentUser.id()));
    }

    @PatchMapping("/api/notification-settings")
    public ApiResponse<NotificationSettingsResponse> updateSettings(
            @RequestBody UpdateNotificationSettingsRequest request
    ) {
        return ApiResponse.of(
                "notification_settings_updated",
                notificationService.updateSettings(currentUser.id(), request)
        );
    }
}
