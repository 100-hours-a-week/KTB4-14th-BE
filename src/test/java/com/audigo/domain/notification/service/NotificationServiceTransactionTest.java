package com.audigo.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.audigo.domain.notification.config.NotificationRealtimeProperties;
import com.audigo.domain.notification.dto.CreateNotificationCommand;
import com.audigo.domain.notification.dto.NotificationResponse;
import com.audigo.domain.notification.entity.Notification;
import com.audigo.domain.notification.entity.NotificationSetting;
import com.audigo.domain.notification.entity.NotificationTargetType;
import com.audigo.domain.notification.entity.NotificationType;
import com.audigo.domain.notification.realtime.NotificationRealtimePublisher;
import com.audigo.domain.notification.repository.NotificationRepository;
import com.audigo.domain.notification.repository.NotificationSettingRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.config.JacksonConfig;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(NotificationServiceTransactionTest.Config.class)
class NotificationServiceTransactionTest {

    private static final Long ROOM_ID = 54L;

    @Autowired private NotificationService service;
    @Autowired private NotificationSseService sseService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserRepository users;
    @Autowired private NotificationSettingRepository settings;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        reset(sseService, redisTemplate);
        transaction = new TransactionTemplate(transactionManager);
        userId = users.saveAndFlush(User.create("수신자", null)).id();
    }

    @AfterEach
    void does_not_bypass_redis_with_direct_sse_delivery() {
        verifyNoInteractions(sseService);
    }

    @Test
    void persists_chat_notification_called_from_after_commit_and_exposes_it_to_fresh_reads() {
        // This is the same transaction boundary used by ChatMessageService.
        transaction.executeWithoutResult(status -> {
            registerChatNotification();
            assertThat(committedNotificationCount()).isZero();
            verifyNoInteractions(redisTemplate);
        });

        assertThat(committedNotificationCount()).isEqualTo(1);
        assertThat(service.list(userId)).singleElement().satisfies(notification -> {
            assertThat(notification.notificationId()).isNotNull();
            assertThat(notification.createdAt()).isNotNull();
            assertThat(notification.type()).isEqualTo(NotificationType.NEW_MESSAGE);
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.CHAT_ROOM);
            assertThat(notification.targetId()).isEqualTo(ROOM_ID);
            assertThat(notification.isRead()).isFalse();
        });
        assertThat(service.unreadCount(userId).count()).isEqualTo(1);
        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    @Test
    void redis_publication_observes_committed_notification_on_an_independent_connection() {
        doAnswer(invocation -> {
            String json = invocation.getArgument(1);
            assertThat(json).contains("\"notification_id\":", "\"recipientUserId\":" + userId);
            assertThat(committedNotificationCount()).isEqualTo(1);
            return 1L;
        }).when(redisTemplate).convertAndSend(anyString(), anyString());

        transaction.executeWithoutResult(status -> registerChatNotification());

        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    @Test
    void rolled_back_chat_transaction_does_not_create_notification_or_publish() {
        transaction.executeWithoutResult(status -> {
            registerChatNotification();
            status.setRollbackOnly();
        });

        assertThat(committedNotificationCount()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void general_notification_publication_waits_for_commit() {
        transaction.executeWithoutResult(status -> {
            service.create(command());
            verifyNoInteractions(redisTemplate);
            assertThat(committedNotificationCount()).isZero();
        });

        assertThat(committedNotificationCount()).isEqualTo(1);
        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    @Test
    void rolled_back_notification_does_not_publish() {
        transaction.executeWithoutResult(status -> {
            service.create(command());
            status.setRollbackOnly();
        });

        assertThat(committedNotificationCount()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void disabled_chat_notifications_are_not_persisted_or_sent() {
        disableNotifications(false, true);

        transaction.executeWithoutResult(status -> registerChatNotification());

        assertThat(committedNotificationCount()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void globally_disabled_notifications_are_not_persisted_or_sent() {
        disableNotifications(true, false);

        transaction.executeWithoutResult(status -> registerChatNotification());

        assertThat(committedNotificationCount()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redis_failure_does_not_undo_persistence_or_fail_the_caller() {
        doThrow(new RedisConnectionFailureException("disconnected"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> service.notifyNewMessage(userId, ROOM_ID, "새 메시지"))
                .doesNotThrowAnyException();

        assertThat(committedNotificationCount()).isEqualTo(1);
        assertThat(service.unreadCount(userId).count()).isEqualTo(1);
        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"MATCH_SUCCESS", "TRAVEL_COMPLETE", "TRAVEL_FAILED"})
    void matching_and_travel_entry_points_use_the_same_committed_publication_path(NotificationType type) {
        transaction.executeWithoutResult(status -> {
            switch (type) {
                case MATCH_SUCCESS -> service.notifyMatchSuccess(userId, 21L, "매칭 성공");
                case TRAVEL_COMPLETE -> service.notifyTravelComplete(userId, 31L, "여행 추천 완료");
                case TRAVEL_FAILED -> service.notifyTravelFailed(userId, 31L, "여행 생성 실패");
                default -> throw new IllegalArgumentException("Unexpected notification type");
            }
            verifyNoInteractions(redisTemplate);
        });

        assertThat(committedNotificationCount()).isEqualTo(1);
        assertThat(service.list(userId)).singleElement().extracting(NotificationResponse::type).isEqualTo(type);
        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    private void registerChatNotification() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                service.notifyNewMessage(userId, ROOM_ID, "상대방님이 메시지를 보냈습니다");
            }
        });
    }

    private CreateNotificationCommand command() {
        return new CreateNotificationCommand(userId, NotificationType.NEW_MESSAGE,
                "새 채팅", "새 메시지", NotificationTargetType.CHAT_ROOM, ROOM_ID);
    }

    private void disableNotifications(boolean chatEnabled, boolean notificationEnabled) {
        transaction.executeWithoutResult(status -> {
            NotificationSetting setting = NotificationSetting.createDefault(users.findById(userId).orElseThrow());
            setting.updateV2(true, chatEnabled, true, notificationEnabled);
            settings.saveAndFlush(setting);
        });
    }

    private long committedNotificationCount() {
        // Suspend any callback's still-bound resources and read from a separate connection.
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return read.execute(status -> new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from notifications where user_id = ?", Long.class, userId));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, NotificationRepository.class})
    @Import({NotificationService.class, NotificationRealtimePublisher.class, JacksonConfig.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:notification-transactions;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName(), Notification.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        NotificationSseService notificationSseService() {
            return mock(NotificationSseService.class);
        }

        @Bean
        StringRedisTemplate redisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        NotificationRealtimeProperties notificationRealtimeProperties() {
            return new NotificationRealtimeProperties("audigo-be", "test", "");
        }
    }
}
