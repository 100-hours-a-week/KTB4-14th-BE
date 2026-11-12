package com.audigo.domain.chat.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.dto.ChatMessageCatchUpQuery;
import com.audigo.domain.chat.dto.ChatRoomDetailQuery;
import com.audigo.domain.chat.dto.SendChatMessageRequest.SendChatMessageCommand;
import com.audigo.domain.chat.entity.*;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.chat.service.*;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.config.JacksonConfig;
import com.audigo.global.error.RateLimitExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringJUnitConfig(ChatMessageSearchTimeIntegrationTest.Config.class)
class ChatMessageSearchTimeIntegrationTest {
    static final String INDEX = "chat-messages-sync-v1-20261109-120000-003", GENERATION = "sync-time-test";
    @Container static final GenericContainer<?> mysql = new GenericContainer<>(DockerImageName.parse("mysql:8.4"))
            .withEnv("MYSQL_ROOT_PASSWORD", "time-test-root-only").withEnv("MYSQL_DATABASE", "audigo_search_sync_lab")
            .withEnv("MYSQL_USER", "audigo").withEnv("MYSQL_PASSWORD", "time-test-only").withEnv("TZ", "UTC")
            .withExposedPorts(3306).waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1));
    @Container static final GenericContainer<?> elastic = new GenericContainer<>(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.6"))
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.ml.enabled", "false").withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m").withExposedPorts(9200)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1024L * 1024 * 1024).withNanoCPUs(1_000_000_000L))
            .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(2)));
    @Autowired ChatMessageService service;
    @Autowired ChatMessageSearchOutboxStore store;
    @Autowired ChatMessageSearchOutboxRepository outbox;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatMessageSearchRepository search;
    @Autowired ChatRoomListRepository roomList;
    @Autowired ChatRoomDetailRepository roomDetail;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatRoomMemberRepository members;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ChatMessageSearchOutboxProperties properties;
    TransactionTemplate tx;
    User sender;
    User recipient;
    ChatRoom room;

    @BeforeEach void prepare() throws Exception {
        String expected = System.getProperty("chat.time.expected-jvm-zone");
        if (expected != null) assertThat(ZoneId.systemDefault().getId()).isEqualTo(expected);
        tx = new TransactionTemplate(manager);
        jdbc.update("DELETE FROM chat_message_search_outbox");
        tx.executeWithoutResult(status -> {
            sender = users.saveAndFlush(User.create("시각발신", null));
            recipient = users.saveAndFlush(User.create("시각수신", null));
            room = rooms.saveAndFlush(ChatRoom.createForMatchConnection(System.nanoTime(), "시각검증"));
            members.saveAndFlush(ChatRoomMember.create(room, sender));
            members.saveAndFlush(ChatRoomMember.create(room, recipient));
        });
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForObject("SELECT NOW(6)=UTC_TIMESTAMP(6)", Boolean.class)).isTrue();
    }

    @Test void service_send_and_raw_outbox_timestamps_share_utc_and_microseconds() throws Exception {
        var response = service.send(sender.id(), room.getId(), new SendChatMessageCommand(UUID.randomUUID().toString(), "시간검증 제주여행"));
        assertThat(response.sentAt().getNano() % 1000).isZero();
        assertThat(rawTime("chat_messages", "sent_at", response.messageId())).isEqualTo(response.sentAt().toLocalDateTime());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE id=? AND sent_at>=UTC_TIMESTAMP(6)-INTERVAL 60 SECOND", Integer.class, response.messageId())).isEqualTo(1);
        var times = jdbc.queryForMap("SELECT CAST(created_at AS CHAR) AS created,CAST(next_attempt_at AS CHAR) AS pending FROM chat_message_search_outbox WHERE message_id=?", response.messageId());
        LocalDateTime created = parse((String) times.get("created"));
        assertThat(created).isBetween(response.sentAt().toLocalDateTime().minusSeconds(1), response.sentAt().toLocalDateTime().plusSeconds(10));
        assertThat(times.get("pending")).isEqualTo(times.get("created"));
        var claim = tx.execute(status -> outbox.claim(GENERATION, INDEX, 30).orElseThrow());
        assertThat(claim.messageId()).isEqualTo(response.messageId());
        assertThat(store.markFailure(claim, true, "ES_IO")).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>last_error_at AND next_attempt_at<=DATE_ADD(last_error_at,INTERVAL 2 SECOND) FROM chat_message_search_outbox WHERE id=?", Boolean.class, claim.id())).isTrue();
    }

    @ParameterizedTest @ValueSource(ints = {0, 123456100, 123456999, 999999999})
    void mysql_jpa_payload_es_and_cursor_agree_at_microsecond_boundaries(int nanos) throws Exception {
        LocalDateTime supplied = LocalDateTime.of(2026, 11, 9, 14, 0, 0, nanos), expected = supplied.truncatedTo(ChronoUnit.MICROS);
        ChatMessage first = tx.execute(status -> {
            ChatMessage message = messages.saveAndFlush(ChatMessage.create(room, sender, UUID.randomUUID().toString(), "시각경계 제주여행", 1, supplied));
            store.enqueue(message); return message;
        });
        assertThat(first.getSentAt()).isEqualTo(expected);
        assertThat(rawTime("chat_messages", "sent_at", first.getId())).isEqualTo(expected);
        var reloaded = tx.execute(status -> messages.findById(first.getId()).orElseThrow().getSentAt());
        assertThat(reloaded).isEqualTo(expected);
        String payload = jdbc.queryForObject("SELECT payload FROM chat_message_search_outbox WHERE message_id=?", String.class, first.getId());
        assertThat(Instant.parse(mapper.readTree(payload).path("sent_at").asText())).isEqualTo(expected.toInstant(ZoneOffset.UTC));
        var second = tx.execute(status -> {
            var message = messages.saveAndFlush(ChatMessage.create(room, sender, UUID.randomUUID().toString(), "시각경계 제주여행", 2, supplied));
            store.enqueue(message); return message;
        });
        var firstPage = tx.execute(status -> search.search(room.getId(), "시각경계", null, null, null, 1));
        assertThat(firstPage).containsExactly(new ChatMessageSearchRepository.Hit(second.getId(), expected));
        var secondPage = tx.execute(status -> search.search(room.getId(), "시각경계", null, expected, second.getId(), 1));
        assertThat(secondPage).containsExactly(new ChatMessageSearchRepository.Hit(first.getId(), expected));
        try (var stream = new ClassPathResource("search/chat-message-sync-mapping.json").getInputStream()) {
            String mapping = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("__TARGET_GENERATION__", GENERATION);
            es("DELETE", "/" + INDEX, null); es("PUT", "/" + INDEX, mapping);
        }
        var claim = tx.execute(status -> outbox.claim(GENERATION, INDEX, 30).orElseThrow());
        new ChatMessageEsIndexer(HttpClient.newHttpClient(), base(), "", properties, mapper).index(claim);
        assertThat(store.markIndexed(claim)).isTrue();
        var source = mapper.readTree(es("GET", "/" + INDEX + "/_doc/" + first.getId(), null).body()).path("_source");
        assertThat(Instant.parse(source.path("sent_at").asText())).isEqualTo(expected.toInstant(ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,created_at,indexed_at) BETWEEN 0 AND 30000000 FROM chat_message_search_outbox WHERE message_id=?", Boolean.class, first.getId())).isTrue();
        var secondClaim = tx.execute(status -> outbox.claim(GENERATION, INDEX, 30).orElseThrow());
        new ChatMessageEsIndexer(HttpClient.newHttpClient(), base(), "", properties, mapper).index(secondClaim);
        assertThat(store.markIndexed(secondClaim)).isTrue();
        // Explicit refresh is only for this isolated cursor correctness check, never the lag benchmark.
        es("POST", "/" + INDEX + "/_refresh", null);
        var esSearch = new ChatMessageEsSearch(HttpClient.newHttpClient(), base(), INDEX, "", mapper, true);
        assertThat(esSearch.search(new ChatRoomDetailQuery(room.getId(), 1, null, null), "시각경계", 1)).isEqualTo(firstPage);
        assertThat(esSearch.search(new ChatRoomDetailQuery(room.getId(), 1, expected, second.getId()), "시각경계", 1)).isEqualTo(secondPage);
    }

    @Test void room_list_detail_and_catchup_keep_utc_and_cursor_order() {
        LocalDateTime expected = LocalDateTime.of(2026, 11, 10, 0, 0, 0, 123456000);
        var created = tx.execute(status -> {
            var managed = rooms.findById(room.getId()).orElseThrow();
            long sequence = managed.recordMessage(expected);
            var first = messages.saveAndFlush(ChatMessage.create(managed, sender, UUID.randomUUID().toString(), "첫 메시지", sequence, expected));
            sequence = managed.recordMessage(expected);
            var second = messages.saveAndFlush(ChatMessage.create(managed, sender, UUID.randomUUID().toString(), "두 번째", sequence, expected));
            var other = rooms.saveAndFlush(ChatRoom.createForMatchConnection(System.nanoTime(), "다른방"));
            members.saveAndFlush(ChatRoomMember.create(other, sender));
            members.saveAndFlush(ChatRoomMember.create(other, recipient));
            other.recordMessage(expected); rooms.flush();
            return List.of(first.getId(), second.getId(), other.getId());
        });
        assertThat(rawTime("chat_rooms", "last_message_at", room.getId())).isEqualTo(expected);
        var firstRoom = tx.execute(status -> roomList.findPage(sender.id(), null, null, null, 1)).getFirst();
        assertThat(firstRoom.roomId()).isEqualTo(room.getId());
        assertThat(firstRoom.lastMessageAt()).isEqualTo(expected);
        assertThat(tx.execute(status -> roomList.findPage(sender.id(), null, expected, room.getId(), 1)).getFirst().roomId()).isEqualTo(created.get(2));
        var newest = tx.execute(status -> roomDetail.findMessages(room.getId(), sender.id(), recipient.id(), null, null, 1)).getFirst();
        assertThat(newest.chatId()).isEqualTo(created.get(1));
        assertThat(newest.sentAt()).isEqualTo(expected);
        assertThat(tx.execute(status -> roomDetail.findMessages(room.getId(), sender.id(), recipient.id(), expected, newest.chatId(), 1)).getFirst().chatId()).isEqualTo(created.getFirst());
        assertThat(tx.execute(status -> roomDetail.findMessage(room.getId(), sender.id(), recipient.id(), created.getFirst()).orElseThrow()).sentAt()).isEqualTo(expected);
        var newer = tx.execute(status -> roomDetail.findNewerMessages(room.getId(), sender.id(), recipient.id(), expected, created.getFirst(), 2));
        assertThat(newer)
                .extracting(ChatRoomDetailRepository.MessageDetailRow::chatId).containsExactly(created.get(1));
        assertThat(service.catchUp(sender.id(), new ChatMessageCatchUpQuery(room.getId(), 0, 100)).messages())
                .extracting(response -> response.sentAt().toLocalDateTime()).containsExactly(expected, expected);
    }

    @Test void utc_rate_window_min_aggregate_and_eleventh_send_remain_correct() {
        List<com.audigo.domain.chat.dto.ChatMessageResponse> sent = new ArrayList<>();
        for (int n = 0; n < 10; n++) sent.add(service.send(sender.id(), room.getId(), new SendChatMessageCommand(UUID.randomUUID().toString(), "시간 전송 제한")));
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(60);
        assertThat(tx.execute(status -> messages.countSentBySenderSince(sender.id(), ChatMessageStatus.SENT, start)).longValue()).isEqualTo(10);
        assertThat(tx.execute(status -> messages.findOldestSentAtBySenderSince(sender.id(), ChatMessageStatus.SENT, start)).orElseThrow()).isEqualTo(sent.getFirst().sentAt().toLocalDateTime());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE sender_id=? AND sent_at>=UTC_TIMESTAMP(6)-INTERVAL 60 SECOND", Integer.class, sender.id())).isEqualTo(10);
        assertThatThrownBy(() -> service.send(sender.id(), room.getId(), new SendChatMessageCommand(UUID.randomUUID().toString(), "차단되어야 함")))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_search_outbox", Integer.class)).isEqualTo(10);
    }

    private LocalDateTime rawTime(String table, String field, long id) {
        return parse(jdbc.queryForObject("SELECT CAST(" + field + " AS CHAR) FROM " + table + " WHERE id=?", String.class, id));
    }
    private static LocalDateTime parse(String text) { return LocalDateTime.parse(text.replace(' ', 'T')); }
    private URI base() { return URI.create("http://" + elastic.getHost() + ":" + elastic.getMappedPort(9200)); }
    private HttpResponse<String> es(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(10));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (!method.equals("DELETE")) assertThat(response.statusCode()).isBetween(200, 201);
        return response;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, ChatMessageRepository.class})
    @Import({ChatMessageService.class, ChatMessageSearchOutboxStore.class, ChatMessageSearchOutboxRepository.class, ChatMessageSearchRepository.class, ChatRoomListRepository.class, ChatRoomDetailRepository.class, JacksonConfig.class})
    static class Config {
        static String url() { return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/audigo_search_sync_lab?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"; }
        @Bean DataSource dataSource() {
            var ds = new DriverManagerDataSource(url(), "audigo", "time-test-only");
            new ResourceDatabasePopulator(new ClassPathResource("db/chat-search-sync/001-create-outbox.sql")).execute(ds); return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan(User.class.getPackageName(), ChatMessage.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Map<String, Object> settings = new HashMap<>(); settings.put("hibernate.hbm2ddl.auto", "create-only");
            factory.setJpaPropertyMap(settings); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory, DataSource source) { var manager = new JpaTransactionManager(factory); manager.setDataSource(source); return manager; }
        @Bean ChatMessageSearchOutboxProperties properties() {
            var env = new MockEnvironment().withProperty("audigo.chat.search-sync.record-enabled", "true")
                    .withProperty("spring.datasource.url", url()).withProperty("audigo.chat.search-sync.target-generation", GENERATION)
                    .withProperty("audigo.chat.search-sync.target-index", INDEX);
            env.setActiveProfiles("search-sync-lab"); return new ChatMessageSearchOutboxProperties(env);
        }
        @Bean ChatRealtimePublisher realtime() { return mock(ChatRealtimePublisher.class); }
        @Bean NotificationService notifications() { return mock(NotificationService.class); }
    }
}
