package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.audigo.domain.travel.config.TravelResultQueueProperties;
import com.audigo.domain.travel.config.TravelSqsProperties;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.messaging.TravelGenerationMessageVersion;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.messaging.TravelGenerationResultType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
class TravelGenerationResultQueueConsumerTest {

    @Mock
    private SqsClient sqsClient;
    @Mock
    private TravelGenerationResultInboxService inboxService;

    private ObjectMapper objectMapper;
    private TravelGenerationResultQueueConsumer consumer;
    private ListAppender<ILoggingEvent> appender;
    private Logger consumerLogger;
    private Logger timingLogger;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        consumerLogger = (Logger) LoggerFactory.getLogger(TravelGenerationResultQueueConsumer.class);
        timingLogger = (Logger) LoggerFactory.getLogger(TravelGenerationResultTiming.class);
        appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
        timingLogger.addAppender(appender);
        consumer = newConsumer(new SyncTaskExecutor(), inboxService);
    }

    @AfterEach
    void tearDown() {
        consumerLogger.detachAppender(appender);
        timingLogger.detachAppender(appender);
        appender.stop();
    }

    private TravelGenerationResultQueueConsumer newConsumer(TaskExecutor executor,
                                                             TravelGenerationResultInboxService inbox) {
        return new TravelGenerationResultQueueConsumer(
                sqsClient,
                new TravelSqsProperties(
                        true,
                        "ap-northeast-2",
                        "https://sqs.example/job",
                        "https://sqs.example/result",
                        ""
                ),
                new TravelResultQueueProperties(20, 5, 2, 20, 5),
                objectMapper,
                inbox,
                executor
        );
    }

    @Test
    void DB_처리가_성공한_뒤_Result_메시지를_삭제한다() throws Exception {
        TravelGenerationResultMessage result = progressMessage();
        Message sqsMessage = message(objectMapper.writeValueAsString(result));
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());
        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);

        consumer.poll();

        ArgumentCaptor<TravelGenerationResultMessage> resultCaptor =
                ArgumentCaptor.forClass(TravelGenerationResultMessage.class);
        verify(inboxService).process(
                resultCaptor.capture(),
                eq("sqs-message-1"),
                anyString(),
                eq(1)
        );
        assertThat(resultCaptor.getValue().eventId()).isEqualTo(result.eventId());
        assertThat(resultCaptor.getValue().generationJobId()).isEqualTo(result.generationJobId());
        assertThat(resultCaptor.getValue().type()).isEqualTo(result.type());
        ArgumentCaptor<DeleteMessageRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().queueUrl()).isEqualTo("https://sqs.example/result");
        assertThat(deleteCaptor.getValue().receiptHandle()).isEqualTo("receipt-1");
    }

    @Test
    void DB_처리가_실패하면_Result_메시지를_삭제하지_않는다() throws Exception {
        TravelGenerationResultMessage result = progressMessage();
        Message sqsMessage = message(objectMapper.writeValueAsString(result));
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());
        doThrow(new IllegalStateException("DB unavailable"))
                .when(inboxService).process(any(), anyString(), anyString(), anyInt());

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 파싱할_수_없는_메시지는_삭제하지_않는다() {
        Message sqsMessage = message("not-json");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());

        consumer.poll();

        verify(inboxService, never()).process(any(), anyString(), anyString(), anyInt());
        verify(inboxService, never()).archiveFailure(anyString(), anyString(), anyInt(), any(), any());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 최대_수신_횟수의_파싱_실패_메시지는_DB에_보관하고_삭제한다() {
        Message sqsMessage = message("not-json", 5);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());

        consumer.poll();

        verify(inboxService).archiveFailure(
                eq("sqs-message-1"),
                eq("not-json"),
                eq(5),
                eq(null),
                any()
        );
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 최대_수신_횟수의_DB_실패_메시지는_DB에_보관하고_삭제한다() throws Exception {
        TravelGenerationResultMessage result = progressMessage();
        Message sqsMessage = message(objectMapper.writeValueAsString(result), 5);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());
        doThrow(new IllegalStateException("DB unavailable"))
                .when(inboxService).process(any(), anyString(), anyString(), anyInt());

        consumer.poll();

        verify(inboxService).archiveFailure(
                eq("sqs-message-1"),
                anyString(),
                eq(5),
                any(TravelGenerationResultMessage.class),
                any()
        );
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 실패_메시지를_DB에_보관하지_못하면_SQS에서_삭제하지_않는다() {
        Message sqsMessage = message("not-json", 5);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());
        doThrow(new IllegalStateException("Inbox unavailable"))
                .when(inboxService).archiveFailure(anyString(), anyString(), anyInt(), any(), any());

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 설정한_Long_Polling과_배치_크기로_Result_Queue를_조회한다() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.poll();

        ArgumentCaptor<ReceiveMessageRequest> receiveCaptor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqsClient).receiveMessage(receiveCaptor.capture());
        assertThat(receiveCaptor.getValue().queueUrl()).isEqualTo("https://sqs.example/result");
        assertThat(receiveCaptor.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(receiveCaptor.getValue().maxNumberOfMessages()).isEqualTo(5);
        assertThat(receiveCaptor.getValue().messageSystemAttributeNames())
                .contains(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                        MessageSystemAttributeName.SENT_TIMESTAMP,
                        MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP);
    }

    @Test
    void 실행기에서_대기하는_동안에도_수신_이력과_횟수를_먼저_기록한다() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        consumer = newConsumer(pending::add, inboxService);
        String body = objectMapper.writeValueAsString(progressMessage());
        Message sqsMessage = message(body, 3).toBuilder().attributes(Map.of(
                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "3",
                MessageSystemAttributeName.SENT_TIMESTAMP, "1793061361348",
                MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP, "1793061361400"
        )).build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());

        consumer.poll();

        assertThat(pending).hasSize(1);
        assertThat(logs()).anySatisfy(entry -> assertThat(entry)
                .contains("travel_generation_result_received", "receive_count=3",
                        "sent_timestamp_ms=1793061361348", "first_receive_timestamp_ms=1793061361400",
                        "body_bytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertThat(logs()).noneMatch(entry -> entry.contains("travel_generation_result_processing_started"));
        verify(inboxService, never()).process(any(), anyString(), anyString(), anyInt());

        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);
        pending.getFirst().run();

        assertThat(logs()).anySatisfy(entry -> assertThat(entry)
                .contains("travel_generation_result_parsed", "event_id=event-1", "job_id=41",
                        "receive_count=3", "executor_wait_ms=", "parse_ms="));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry)
                .contains("stage=transaction", "outcome=SUCCESS"));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry)
                .contains("travel_generation_result_processing_finished", "receive_to_delete_ms="));
    }

    @Test
    void poller가_직접_처리해도_배치_전체의_수신_로그를_먼저_남긴다() throws Exception {
        String body = objectMapper.writeValueAsString(progressMessage());
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(
                        message(body), message(body).toBuilder().messageId("sqs-message-2").build()).build());
        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);

        consumer.poll();

        List<String> entries = logs();
        String secondReceived = entries.stream().filter(entry -> entry.contains("travel_generation_result_received")
                && entry.contains("sqs_message_id=sqs-message-2")).findFirst().orElseThrow();
        String firstStarted = entries.stream().filter(entry -> entry.contains("travel_generation_result_processing_started"))
                .findFirst().orElseThrow();
        assertThat(entries.indexOf(secondReceived)).isLessThan(entries.indexOf(firstStarted));
    }

    @Test
    void 잘못된_수신_시각_속성으로_처리를_중단하거나_원문을_로그에_남기지_않는다() throws Exception {
        var payload = objectMapper.createObjectNode().put("private", "비공개여행요청");
        TravelGenerationResultMessage original = progressMessage();
        String body = objectMapper.writeValueAsString(new TravelGenerationResultMessage(
                original.eventId(), original.generationJobId(), original.messageVersion(), original.occurredAt(),
                original.type(), original.stage(), original.state(), payload, null));
        Message sqsMessage = message(body).toBuilder().attributes(Map.of(
                MessageSystemAttributeName.SENT_TIMESTAMP, "invalid",
                MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP, "-1"
        )).build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());
        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);

        consumer.poll();

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry).contains(
                "sent_timestamp_ms=-1", "first_receive_timestamp_ms=-1", "sent_to_received_ms=-1",
                "body_bytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertThat(logs()).noneMatch(entry -> entry.contains("비공개여행요청") || entry.contains("receipt-1"));
    }

    @Test
    void 트랜잭션_커밋이_실패하면_성공으로_기록하거나_메시지를_삭제하지_않는다() throws Exception {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        doThrow(new IllegalStateException("commit unavailable")).when(transactionManager).commit(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new MatchAlwaysTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(inboxService);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        consumer = newConsumer(new SyncTaskExecutor(), (TravelGenerationResultInboxService) proxyFactory.getProxy());
        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                ReceiveMessageResponse.builder().messages(message(objectMapper.writeValueAsString(progressMessage()))).build());

        consumer.poll();

        verify(transactionManager).commit(transactionStatus);
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry).contains("stage=transaction", "outcome=FAILURE"));
        assertThat(logs()).noneMatch(entry -> entry.contains("travel_generation_result_processing_finished"));
    }

    @Test
    void Inbox_처리_반환_후_삭제가_실패하면_구간을_구분하고_전체_성공을_기록하지_않는다() throws Exception {
        when(inboxService.process(any(), anyString(), anyString(), anyInt()))
                .thenReturn(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                ReceiveMessageResponse.builder().messages(message(objectMapper.writeValueAsString(progressMessage()))).build());
        doThrow(new IllegalStateException("delete unavailable")).when(sqsClient).deleteMessage(any(DeleteMessageRequest.class));

        consumer.poll();

        var order = inOrder(inboxService, sqsClient);
        order.verify(inboxService).process(any(), anyString(), anyString(), anyInt());
        order.verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry).contains("stage=transaction", "outcome=SUCCESS"));
        assertThat(logs()).anySatisfy(entry -> assertThat(entry).contains("stage=delete", "outcome=FAILURE"));
        assertThat(logs()).noneMatch(entry -> entry.contains("travel_generation_result_processing_finished"));
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private TravelGenerationResultMessage progressMessage() {
        return new TravelGenerationResultMessage(
                "event-1",
                41L,
                TravelGenerationMessageVersion.V1,
                Instant.parse("2026-10-17T01:00:00Z"),
                TravelGenerationResultType.PROGRESS,
                TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.RUNNING,
                null,
                null
        );
    }

    private Message message(String body) {
        return message(body, null);
    }

    private Message message(String body, Integer receiveCount) {
        Message.Builder builder = Message.builder()
                .messageId("sqs-message-1")
                .receiptHandle("receipt-1")
                .body(body);
        if (receiveCount != null) {
            builder.attributes(java.util.Map.of(
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                    receiveCount.toString()
            ));
        }
        return builder.build();
    }
}
