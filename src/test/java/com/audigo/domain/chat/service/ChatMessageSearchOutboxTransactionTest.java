package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.dto.SendChatMessageRequest.SendChatMessageCommand;
import com.audigo.domain.chat.entity.*;
import com.audigo.domain.chat.repository.*;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.config.JacksonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringJUnitConfig(ChatMessageSearchOutboxTransactionTest.Config.class)
class ChatMessageSearchOutboxTransactionTest {
    @Container
    static final GenericContainer<?> mysql = new GenericContainer<>(DockerImageName.parse("mysql:8.4"))
            .withEnv("MYSQL_ROOT_PASSWORD", "sync-test-root-only")
            .withEnv("MYSQL_DATABASE", "audigo_search_sync_lab")
            .withEnv("MYSQL_USER", "audigo").withEnv("MYSQL_PASSWORD", "sync-test-only")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1));

    @Autowired ChatMessageService service;
    @Autowired ChatMessageSearchOutboxStore store;
    @Autowired ChatMessageSearchOutboxRepository outbox;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatRoomMemberRepository members;
    @Autowired ChatMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChatRealtimePublisher realtime;
    @Autowired NotificationService notifications;
    @Autowired ObjectMapper mapper;
    private TransactionTemplate tx;
    private Long senderId,roomId;
    private String clientId;

    @BeforeEach void prepare() {
        reset(realtime, notifications);
        tx = new TransactionTemplate(manager);
        clientId = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            User sender = users.saveAndFlush(User.create("발신자", null));
            User recipient = users.saveAndFlush(User.create("수신자", null));
            ChatRoom room = rooms.saveAndFlush(ChatRoom.createForMatchConnection(System.nanoTime(), "동기화검증"));
            members.saveAndFlush(ChatRoomMember.create(room, sender));
            members.saveAndFlush(ChatRoomMember.create(room, recipient));
            senderId = sender.id(); roomId = room.getId();
        });
    }

    private SendChatMessageCommand command() { return new SendChatMessageCommand(clientId, "제주여행 cafe\u0301 😀"); }
    private long committedCount(String table) {
        // Uses an independent connection, outside the JPA-bound transaction.
        String sql = "SELECT COUNT(*) FROM " + table +
                (table.equals("chat_messages") ? " WHERE chat_room_id=?" : " WHERE JSON_EXTRACT(payload,'$.room_id')=?");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, roomId);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
    }

    @Test void message_and_event_become_visible_together_after_commit() throws Exception {
        var result = tx.execute(status -> {
            var response = service.send(senderId, roomId, command());
            assertThat(committedCount("chat_messages")).isZero();
            assertThat(committedCount("chat_message_search_outbox")).isZero();
            verifyNoInteractions(realtime, notifications);
            return response;
        });
        assertThat(committedCount("chat_messages")).isEqualTo(1);
        assertThat(committedCount("chat_message_search_outbox")).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM chat_message_search_outbox WHERE message_id=?", result.messageId());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number)row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("target_generation")).isEqualTo("sync-v1-test");
        assertThat(row.get("target_index")).isEqualTo("chat-messages-sync-v1-20261109-120000-001");
        var payload = mapper.readTree((String)row.get("payload"));
        assertThat(payload.path("message_id").longValue()).isEqualTo(result.messageId());
        assertThat(payload.path("room_id").longValue()).isEqualTo(roomId);
        assertThat(payload.path("content").asText()).isEqualTo(command().content());
        assertThat(payload.path("text_contract").asText()).isEqualTo("nfc-v1");
        assertThat(payload.path("sent_at").asText()).endsWith("Z");
        assertThat(java.time.Instant.parse(payload.path("sent_at").asText()))
                .isEqualTo(result.sentAt().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test void outer_rollback_removes_message_event_room_sequence_and_callbacks() {
        tx.executeWithoutResult(status -> { service.send(senderId, roomId, command()); status.setRollbackOnly(); });
        assertThat(committedCount("chat_messages")).isZero();
        assertThat(committedCount("chat_message_search_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_message_sequence FROM chat_rooms WHERE id=?", Long.class, roomId)).isZero();
        verifyNoInteractions(realtime, notifications);
    }

    @Test void replay_does_not_create_a_second_message_or_event() {
        var first = service.sendWithStatus(senderId, roomId, command());
        var second = service.sendWithStatus(senderId, roomId, command());
        assertThat(first.created()).isTrue(); assertThat(second.created()).isFalse();
        assertThat(second.response().messageId()).isEqualTo(first.response().messageId());
        assertThat(committedCount("chat_messages")).isEqualTo(1);
        assertThat(committedCount("chat_message_search_outbox")).isEqualTo(1);
    }

    @Test void outbox_constraint_failure_rolls_back_the_new_message() {
        jdbc.execute("ALTER TABLE chat_message_search_outbox ADD CONSTRAINT sync_test_reject CHECK (JSON_EXTRACT(payload,'$.room_id') <> " + roomId + ")");
        try {
            assertThatThrownBy(() -> service.send(senderId, roomId, command())).isInstanceOf(DataAccessException.class);
            assertThat(committedCount("chat_messages")).isZero();
            assertThat(committedCount("chat_message_search_outbox")).isZero();
            assertThat(jdbc.queryForObject("SELECT last_message_sequence FROM chat_rooms WHERE id=?", Long.class, roomId)).isZero();
            verifyNoInteractions(realtime, notifications);
        } finally { jdbc.execute("ALTER TABLE chat_message_search_outbox DROP CHECK sync_test_reject"); }
    }

    @Test void database_unique_key_rejects_duplicate_generation_and_preserves_original_event() {
        var response = service.send(senderId, roomId, command());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.enqueue(messages.findById(response.messageId()).orElseThrow())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(committedCount("chat_message_search_outbox")).isEqualTo(1);
    }

    @Test void enqueue_without_message_transaction_is_rejected() {
        assertThatThrownBy(() -> store.enqueue(null)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void disabled_recording_does_not_need_or_query_the_outbox_table() {
        var disabledRepository = mock(ChatMessageSearchOutboxRepository.class);
        var disabled = new ChatMessageSearchOutboxStore(new ChatMessageSearchOutboxProperties(new MockEnvironment()),
                disabledRepository, mapper);
        tx.executeWithoutResult(status -> disabled.enqueue(null));
        verifyNoInteractions(disabledRepository);
        assertThat(committedCount("chat_message_search_outbox")).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, ChatMessageRepository.class})
    @Import({ChatMessageService.class, ChatMessageSearchOutboxStore.class, ChatMessageSearchOutboxRepository.class, JacksonConfig.class})
    static class Config {
        static String url() { return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/audigo_search_sync_lab?serverTimezone=UTC&characterEncoding=UTF-8"; }
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(url(), "audigo", "sync-test-only");
            new ResourceDatabasePopulator(new ClassPathResource("db/chat-search-sync/001-create-outbox.sql")).execute(source);
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(User.class.getPackageName(), ChatMessage.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory, DataSource source) {
            var manager = new JpaTransactionManager(factory); manager.setDataSource(source); return manager;
        }
        @Bean ChatMessageSearchOutboxProperties outboxProperties() {
            var env = new MockEnvironment().withProperty("audigo.chat.search-sync.record-enabled", "true")
                    .withProperty("spring.datasource.url", url())
                    .withProperty("audigo.chat.search-sync.target-generation", "sync-v1-test")
                    .withProperty("audigo.chat.search-sync.target-index", "chat-messages-sync-v1-20261109-120000-001");
            env.setActiveProfiles("search-sync-lab");
            return new ChatMessageSearchOutboxProperties(env);
        }
        @Bean ChatRealtimePublisher realtime() { return mock(ChatRealtimePublisher.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }
}
