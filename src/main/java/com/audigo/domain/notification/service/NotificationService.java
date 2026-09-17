package com.audigo.domain.notification.service;

import com.audigo.domain.notification.dto.CreateNotificationCommand;
import com.audigo.domain.notification.dto.NotificationCreateResult;
import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.dto.NotificationSettingsResponse;
import com.audigo.domain.notification.dto.ReadNotificationsRequest;
import com.audigo.domain.notification.dto.UnreadCountResponse;
import com.audigo.domain.notification.dto.UpdateNotificationSettingsRequest;
import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationSetting;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.domain.notification.repository.NotificationRepository;
import com.audigo.domain.notification.repository.NotificationSettingRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final UserRepository userRepository;
    private final NotificationSseService notificationSseService;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSettingRepository notificationSettingRepository,
            UserRepository userRepository,
            NotificationSseService notificationSseService
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationSettingRepository = notificationSettingRepository;
        this.userRepository = userRepository;
        this.notificationSseService = notificationSseService;
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
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead();
    }

    @Transactional
    public void markRead(Long userId, ReadNotificationsRequest request) {
        List<Notification> notifications = notificationRepository.findByIdInAndUser_Id(request.notificationIds(), userId);
        if (notifications.size() != request.notificationIds().size()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        notifications.forEach(Notification::markRead);
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.findByUser_IdAndReadFalse(userId)
                .forEach(Notification::markRead);
    }

    @Transactional
    public NotificationSettingsResponse getSettings(Long userId) {
        return NotificationSettingsResponse.from(findOrCreateSettings(userId));
    }

    @Transactional
    public NotificationSettingsResponse updateSettings(Long userId, UpdateNotificationSettingsRequest request) {
        NotificationSetting setting = findOrCreateSettings(userId);
        setting.update(
                resolve(request.matchSuccessEnabled(), request.travelReady(), setting.matchSuccessEnabled()),
                resolve(request.chatEnabled(), request.newChat(), setting.chatEnabled()),
                resolve(request.travelBeforeEnabled(), request.travelD1(), setting.travelBeforeEnabled()),
                resolve(request.travelCompleteEnabled(), request.travelFailed(), setting.travelCompleteEnabled()),
                resolve(request.notificationEnabled(), null, setting.notificationEnabled())
        );
        return NotificationSettingsResponse.from(setting);
    }

    public SseEmitter subscribe(Long userId) {
        return notificationSseService.subscribe(userId);
    }

    @Transactional
    public NotificationCreateResult create(CreateNotificationCommand command) {
        User user = findActiveUser(command.userId());
        NotificationSetting setting = findOrCreateSettings(user.id());
        if (!setting.enabledFor(command.notificationType())) {
            log.info("notification_skipped user_id={} type={} reason=disabled", user.id(), command.notificationType());
            return NotificationCreateResult.skipped();
        }

        Notification notification = notificationRepository.save(Notification.create(
                user,
                command.notificationType(),
                command.title(),
                command.content(),
                command.targetType(),
                command.targetId()
        ));
        NotificationResponse response = NotificationResponse.from(notification);
        notificationSseService.sendNotification(user.id(), response);
        log.info("notification_created user_id={} notification_id={} type={} target_type={} target_id={}",
                user.id(),
                notification.id(),
                command.notificationType(),
                command.targetType(),
                command.targetId()
        );
        return NotificationCreateResult.created(response);
    }

    @Transactional
    public NotificationCreateResult notifyMatchSuccess(Long userId, Long matchConnectionId, String content) {
        return create(new CreateNotificationCommand(
                userId,
                NotificationType.MATCH_SUCCESS,
                "매칭 성공",
                content,
                NotificationTargetType.MATCH_CONNECTION,
                matchConnectionId
        ));
    }

    @Transactional
    public NotificationCreateResult notifyNewMessage(Long userId, Long chatRoomId, String content) {
        return create(new CreateNotificationCommand(
                userId,
                NotificationType.NEW_MESSAGE,
                "새 채팅",
                content,
                NotificationTargetType.CHAT_ROOM,
                chatRoomId
        ));
    }

    @Transactional
    public NotificationCreateResult notifyTravelBefore(Long userId, Long travelPlanId, String content) {
        return create(new CreateNotificationCommand(
                userId,
                NotificationType.TRAVEL_BEFORE,
                "여행 D-1",
                content,
                NotificationTargetType.TRAVEL_PLAN,
                travelPlanId
        ));
    }

    @Transactional
    public NotificationCreateResult notifyTravelComplete(Long userId, Long travelPlanId, String content) {
        return create(new CreateNotificationCommand(
                userId,
                NotificationType.TRAVEL_COMPLETE,
                "여행 추천 완료",
                content,
                NotificationTargetType.TRAVEL_PLAN,
                travelPlanId
        ));
    }

    @Transactional
    public NotificationCreateResult notifyTravelFailed(Long userId, Long travelPlanId, String content) {
        return create(new CreateNotificationCommand(
                userId,
                NotificationType.TRAVEL_FAILED,
                "여행 일정 생성 실패",
                content,
                NotificationTargetType.TRAVEL_PLAN,
                travelPlanId
        ));
    }

    private NotificationSetting findOrCreateSettings(Long userId) {
        return notificationSettingRepository.findByUser_Id(userId)
                .orElseGet(() -> {
                    User user = findActiveUser(userId);
                    return notificationSettingRepository.save(NotificationSetting.createDefault(user));
                });
    }

    private User findActiveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        return user;
    }

    private boolean resolve(Boolean currentNameValue, Boolean legacyNameValue, boolean fallback) {
        if (currentNameValue != null) {
            return currentNameValue;
        }
        if (legacyNameValue != null) {
            return legacyNameValue;
        }
        return fallback;
    }
}
