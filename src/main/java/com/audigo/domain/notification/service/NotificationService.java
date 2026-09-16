package com.audigo.domain.notification.service;

import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.dto.NotificationSettingsResponse;
import com.audigo.domain.notification.dto.UnreadCountResponse;
import com.audigo.domain.notification.dto.UpdateNotificationSettingsRequest;
import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationSetting;
import com.audigo.domain.notification.repository.NotificationRepository;
import com.audigo.domain.notification.repository.NotificationSettingRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final UserRepository userRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSettingRepository notificationSettingRepository,
            UserRepository userRepository
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationSettingRepository = notificationSettingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId) {
        return notificationRepository.findByUser_IdOrderByCreatedAtDesc(userId)
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(Long userId) {
        return new UnreadCountResponse(notificationRepository.countByUser_IdAndReadFalse(userId));
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUser_Id(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        notification.markRead();
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.findByUser_IdOrderByCreatedAtDesc(userId)
                .forEach(Notification::markRead);
    }

    @Transactional
    public NotificationSettingsResponse getSettings(Long userId) {
        return NotificationSettingsResponse.from(findOrCreateSettings(userId));
    }

    @Transactional
    public NotificationSettingsResponse updateSettings(Long userId, UpdateNotificationSettingsRequest request) {
        NotificationSetting setting = findOrCreateSettings(userId);
        setting.update(request.travelReady(), request.newChat(), request.travelD1(), request.travelFailed());
        return NotificationSettingsResponse.from(setting);
    }

    private NotificationSetting findOrCreateSettings(Long userId) {
        return notificationSettingRepository.findByUser_Id(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
                    return notificationSettingRepository.save(NotificationSetting.createDefault(user));
                });
    }
}
