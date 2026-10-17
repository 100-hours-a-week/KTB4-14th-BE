package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
class TravelGenerationResultQueueConsumerTest {

    @Mock
    private SqsClient sqsClient;
    @Mock
    private TravelGenerationJobService jobService;

    private ObjectMapper objectMapper;
    private TravelGenerationResultQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        consumer = new TravelGenerationResultQueueConsumer(
                sqsClient,
                new TravelSqsProperties(
                        true,
                        "ap-northeast-2",
                        "https://sqs.example/job",
                        "https://sqs.example/result",
                        ""
                ),
                new TravelResultQueueProperties(20, 5, 2, 20),
                objectMapper,
                jobService,
                new SyncTaskExecutor()
        );
    }

    @Test
    void DB_처리가_성공한_뒤_Result_메시지를_삭제한다() throws Exception {
        TravelGenerationResultMessage result = progressMessage();
        Message sqsMessage = message(objectMapper.writeValueAsString(result));
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());

        consumer.poll();

        ArgumentCaptor<TravelGenerationResultMessage> resultCaptor =
                ArgumentCaptor.forClass(TravelGenerationResultMessage.class);
        verify(jobService).handleResultMessage(resultCaptor.capture());
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
                .when(jobService).handleResultMessage(any(TravelGenerationResultMessage.class));

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void 파싱할_수_없는_메시지는_삭제하지_않는다() {
        Message sqsMessage = message("not-json");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(sqsMessage).build());

        consumer.poll();

        verify(jobService, never()).handleResultMessage(any());
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
        return Message.builder()
                .messageId("sqs-message-1")
                .receiptHandle("receipt-1")
                .body(body)
                .build();
    }
}
