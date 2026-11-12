package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.*;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.entity.ChatMessageSearchOutbox;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository.ClaimedEvent;
import com.audigo.global.config.JacksonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringJUnitConfig(ChatMessageSearchSyncWorkerIntegrationTest.Config.class)
class ChatMessageSearchSyncWorkerIntegrationTest {
    static final String INDEX="chat-messages-sync-v1-20261109-120000-002";
    static final String GENERATION="sync-worker-test";
    @Container static final GenericContainer<?> mysql=new GenericContainer<>(DockerImageName.parse("mysql:8.4"))
            .withEnv("MYSQL_ROOT_PASSWORD","sync-test-root-only").withEnv("MYSQL_DATABASE","audigo_search_sync_lab")
            .withEnv("MYSQL_USER","audigo").withEnv("MYSQL_PASSWORD","sync-test-only").withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n",1));
    @Container static final GenericContainer<?> elastic=new GenericContainer<>(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.6"))
            .withEnv("discovery.type","single-node").withEnv("xpack.security.enabled","false")
            .withEnv("xpack.ml.enabled","false").withEnv("ES_JAVA_OPTS","-Xms256m -Xmx256m").withExposedPorts(9200)
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withMemory(1024L*1024*1024).withNanoCPUs(1_000_000_000L))
            .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(2)));
    @Autowired ChatMessageSearchOutboxStore store;
    @Autowired ChatMessageSearchOutboxRepository repository;
    @Autowired ChatMessageSearchSyncWorker worker;
    @Autowired ChatMessageEsIndexer indexer;
    @Autowired ChatMessageSearchOutboxProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    private final HttpClient http=HttpClient.newHttpClient();

    static URI esBase() {return URI.create("http://"+elastic.getHost()+":"+elastic.getMappedPort(9200));}
    HttpResponse<String> es(String method,String path,String body)throws Exception {
        var request=HttpRequest.newBuilder(esBase().resolve(path)).timeout(Duration.ofSeconds(10));
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @BeforeEach void reset()throws Exception {
        jdbc.update("DELETE FROM chat_message_search_outbox");
        es("DELETE","/"+INDEX,null);
        String mapping=new String(new ClassPathResource("search/chat-message-sync-mapping.json").getInputStream().readAllBytes(),StandardCharsets.UTF_8)
                .replace("__TARGET_GENERATION__",GENERATION);
        assertThat(es("PUT","/"+INDEX,mapping).statusCode()).isEqualTo(200);
    }
    void enqueue(long id)throws Exception {enqueue(id,GENERATION,INDEX);}
    void enqueue(long id,String generation,String target)throws Exception {
        String payload=mapper.writeValueAsString(Map.of("schema_version",1,"event_type","CHAT_MESSAGE_CREATED","text_contract","nfc-v1",
                "message_id",id,"room_id",1003L,"status","SENT","sent_at","2026-11-09T01:00:00.123456Z","content","제주여행 cafe\u0301 😀"));
        new TransactionTemplate(manager).executeWithoutResult(tx->repository.insert(ChatMessageSearchOutbox.create(id,UUID.randomUUID().toString(),generation,target,payload,LocalDateTime.now(ZoneOffset.UTC))));
    }
    Map<String,Object> row(long id){return jdbc.queryForMap("SELECT * FROM chat_message_search_outbox WHERE message_id=?",id);}
    void expire(ClaimedEvent e){jdbc.update("UPDATE chat_message_search_outbox SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",e.id());}
    void ready(){jdbc.update("UPDATE chat_message_search_outbox SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE status='PENDING'");}

    @Test void real_es_preserves_source_normalizes_search_and_keeps_long_ids()throws Exception {
        long id=9_007_199_254_741_001L;enqueue(id);
        assertThat(worker.processOne()).isTrue();
        assertThat(row(id).get("status")).isEqualTo("INDEXED");
        var source=mapper.readTree(es("GET","/"+INDEX+"/_doc/"+id,null).body()).path("_source");
        assertThat(source.path("message_id").longValue()).isEqualTo(id);
        assertThat(source.path("content").asText()).isEqualTo("제주여행 cafe\u0301 😀");
        assertThat(source.path("content_nfc").asText()).isEqualTo("제주여행 café 😀");
        assertThat(source.path("sent_at").asText()).isEqualTo("2026-11-09T01:00:00.123456Z");
        es("POST","/"+INDEX+"/_refresh",null);
        var hits=mapper.readTree(es("POST","/"+INDEX+"/_search","{\"query\":{\"match_phrase\":{\"content_nfc\":\"café\"}}}").body()).path("hits").path("hits");
        assertThat(hits).hasSize(1);assertThat(hits.get(0).path("_id").asText()).isEqualTo(Long.toString(id));
    }

    @Test void lease_recovery_after_es_success_replays_one_document_and_rejects_old_owner()throws Exception {
        enqueue(11);var first=store.claim().orElseThrow();indexer.index(first);expire(first);
        assertThat(store.markIndexed(first)).isFalse();
        var second=store.claim().orElseThrow();
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(store.markIndexed(first)).isFalse();
        assertThat(store.markFailure(first,true,"ES_IO")).isFalse();
        indexer.index(second);assertThat(store.markIndexed(second)).isTrue();
        es("POST","/"+INDEX+"/_refresh",null);
        assertThat(mapper.readTree(es("GET","/"+INDEX+"/_count",null).body()).path("count").intValue()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints={429,503})
    void transient_http_failures_retry_and_recover(int status)throws Exception {
        enqueue(12);
        HttpServer server=faultServer(status,false);
        try {new ChatMessageSearchSyncWorker(store,faultIndexer(server),properties).processOne();}
        finally {server.stop(0);}
        assertThat(row(12).get("status")).isEqualTo("PENDING");
        assertThat(row(12).get("last_error_code")).isEqualTo("ES_HTTP_"+status);
        assertThat(row(12).get("attempt_count")).isEqualTo(1);
        // The backoff is checked in the DB; no timing-dependent assertion on the next poll.
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>last_error_at FROM chat_message_search_outbox WHERE message_id=12",Boolean.class)).isTrue();
        ready();assertThat(worker.processOne()).isTrue();assertThat(row(12).get("status")).isEqualTo("INDEXED");
    }

    @Test void request_timeout_is_retryable_and_safe_error_is_stored()throws Exception {
        enqueue(13);HttpServer server=faultServer(503,true);
        try {new ChatMessageSearchSyncWorker(store,faultIndexer(server),properties).processOne();}
        finally {server.stop(0);}
        assertThat(row(13).get("status")).isEqualTo("PENDING");
        assertThat(row(13).get("last_error_code")).isEqualTo("ES_IO");
    }

    @Test void permanent_failure_does_not_block_the_next_message()throws Exception {
        enqueue(14);HttpServer server=faultServer(400,false);
        try {new ChatMessageSearchSyncWorker(store,faultIndexer(server),properties).processOne();}
        finally {server.stop(0);}
        assertThat(row(14).get("status")).isEqualTo("FAILED");
        enqueue(15);assertThat(worker.processOne()).isTrue();assertThat(row(15).get("status")).isEqualTo("INDEXED");
    }

    @Test void missing_index_is_not_intentionally_auto_created()throws Exception {
        enqueue(16);es("DELETE","/"+INDEX,null);worker.processOne();
        assertThat(row(16).get("status")).isEqualTo("FAILED");
        assertThat(row(16).get("last_error_code")).isEqualTo("ES_HTTP_404");
        assertThat(es("HEAD","/"+INDEX,null).statusCode()).isEqualTo(404);
    }

    @Test void competing_workers_claim_different_rows_and_ignore_another_generation()throws Exception {
        enqueue(17);enqueue(18);enqueue(19,"another-generation",INDEX);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<ClaimedEvent> claim=()->{start.await();return store.claim().orElseThrow();};
            var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();
            assertThat(Set.of(a.get(10,TimeUnit.SECONDS).messageId(),b.get(10,TimeUnit.SECONDS).messageId())).containsExactlyInAnyOrder(17L,18L);
        }
        assertThat(store.claim()).isEmpty();assertThat(row(19).get("status")).isEqualTo("PENDING");
    }

    @Test void es_http_is_refused_inside_database_transaction()throws Exception {
        enqueue(20);var event=store.claim().orElseThrow();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->indexer.index(event))).isInstanceOf(IllegalStateException.class);
    }

    @Test void retry_delay_is_bounded() {
        assertThat(store.retryDelaySeconds(1)).isEqualTo(1);assertThat(store.retryDelaySeconds(2)).isEqualTo(2);
        assertThat(store.retryDelaySeconds(100)).isEqualTo(60);
    }

    ChatMessageEsIndexer faultIndexer(HttpServer server) {
        return new ChatMessageEsIndexer(http,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"",properties,mapper);
    }
    HttpServer faultServer(int status,boolean slow)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body;
            int response;
            if(exchange.getRequestMethod().equals("GET")) {
                response=200;body=("{\""+INDEX+"\":{\"mappings\":{\"_meta\":{\"text_contract\":\"nfc-v1\",\"sync_generation\":\""+GENERATION+"\"}}}}").getBytes(StandardCharsets.UTF_8);
            } else {
                if(slow)try{Thread.sleep(1500);}catch(InterruptedException exception){Thread.currentThread().interrupt();}
                response=status;body="private response body must not be stored".getBytes(StandardCharsets.UTF_8);
            }
            try {exchange.sendResponseHeaders(response,body.length);exchange.getResponseBody().write(body);}
            finally {exchange.close();}
        });server.start();return server;
    }

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    @Import({ChatMessageSearchOutboxStore.class,ChatMessageSearchOutboxRepository.class,JacksonConfig.class})
    static class Config {
        @Bean DataSource dataSource(){
            var ds=new DriverManagerDataSource("jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/audigo_search_sync_lab?serverTimezone=UTC","audigo","sync-test-only");
            new ResourceDatabasePopulator(new ClassPathResource("db/chat-search-sync/001-create-outbox.sql")).execute(ds);return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ChatMessageSearchOutboxProperties properties(){
            var env=new MockEnvironment().withProperty("audigo.chat.search-sync.worker-enabled","true")
                    .withProperty("audigo.chat.search-sync.target-generation",GENERATION).withProperty("audigo.chat.search-sync.target-index",INDEX)
                    .withProperty("audigo.chat.search-sync.request-timeout-seconds","1")
                    .withProperty("spring.datasource.url","jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/audigo_search_sync_lab");
            env.setActiveProfiles("search-sync-lab");return new ChatMessageSearchOutboxProperties(env);
        }
        @Bean ChatMessageEsIndexer indexer(ChatMessageSearchOutboxProperties p,ObjectMapper m){return new ChatMessageEsIndexer(HttpClient.newHttpClient(),esBase(),"",p,m);}
        @Bean ChatMessageSearchSyncWorker worker(ChatMessageSearchOutboxStore s,ChatMessageEsIndexer i,ChatMessageSearchOutboxProperties p){return new ChatMessageSearchSyncWorker(s,i,p);}
    }
}
