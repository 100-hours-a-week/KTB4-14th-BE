package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.config.TravelSqsProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@ExtendWith(MockitoExtension.class)
class TravelGenerationOutboxPublisherTest {

    @Mock
    private TravelGenerationOutboxStore outboxStore;

    @Mock
    private SqsClient sqsClient;

    private TravelGenerationOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TravelGenerationOutboxPublisher(
                outboxStore,
                new TravelSqsProperties(
                        true,
                        "ap-northeast-2",
                        "https://sqs.example/job",
                        "https://sqs.example/result",
                        ""
                ),
                sqsClient
        );
    }

    @Test
    void Job_Queue_전송이_성공하면_발행_완료를_기록한다() {
        var event = new TravelGenerationOutboxStore.ClaimedEvent(
                1L,
                "event-1",
                "{\"generation_job_id\":41}",
                0
        );
        when(outboxStore.claimReadyEvents()).thenReturn(List.of(event));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-message-1").build());

        publisher.publishPendingEvents();

        ArgumentCaptor<SendMessageRequest> requestCaptor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().queueUrl()).isEqualTo("https://sqs.example/job");
        assertThat(requestCaptor.getValue().messageBody()).isEqualTo(event.payload());
        verify(outboxStore).markPublished(1L);
        verify(outboxStore, never()).markForRetry(any(), any());
    }

    @Test
    void Job_Queue_전송이_실패하면_재시도를_예약한다() {
        var event = new TravelGenerationOutboxStore.ClaimedEvent(
                1L,
                "event-1",
                "{\"generation_job_id\":41}",
                0
        );
        when(outboxStore.claimReadyEvents()).thenReturn(List.of(event));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new IllegalStateException("SQS unavailable"));

        publisher.publishPendingEvents();

        verify(outboxStore).markForRetry(1L, "SQS unavailable");
        verify(outboxStore, never()).markPublished(any());
    }
}
