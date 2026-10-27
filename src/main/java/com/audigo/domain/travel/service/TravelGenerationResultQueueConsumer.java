package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TravelResultQueueProperties;
import com.audigo.domain.travel.config.TravelSqsProperties;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(prefix = "audigo.sqs", name = "enabled", havingValue = "true")
public class TravelGenerationResultQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationResultQueueConsumer.class);

    private final SqsClient sqsClient;
    private final TravelSqsProperties sqsProperties;
    private final TravelResultQueueProperties consumerProperties;
    private final ObjectMapper objectMapper;
    private final TravelGenerationResultInboxService inboxService;
    private final TaskExecutor taskExecutor;

    public TravelGenerationResultQueueConsumer(
            SqsClient sqsClient,
            TravelSqsProperties sqsProperties,
            TravelResultQueueProperties consumerProperties,
            ObjectMapper objectMapper,
            TravelGenerationResultInboxService inboxService,
            @Qualifier("travelResultQueueExecutor") TaskExecutor taskExecutor
    ) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.consumerProperties = consumerProperties;
        this.objectMapper = objectMapper;
        this.inboxService = inboxService;
        this.taskExecutor = taskExecutor;
        log.info("travel_generation_result_consumer_config queue_url={} concurrency={} executor_queue_capacity={} max_messages={} wait_time_seconds={} max_receive_count={}",
                sqsProperties.resultQueueUrl(), consumerProperties.concurrency(),
                consumerProperties.executorQueueCapacity(), consumerProperties.maxMessages(),
                consumerProperties.waitTimeSeconds(), consumerProperties.maxReceiveCount());
    }

    @Scheduled(
            fixedDelayString = "${audigo.sqs.result-consumer.poller-delay-millis:1000}",
            scheduler = "travelResultQueueScheduler"
    )
    public void poll() {
        try {
            var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(sqsProperties.resultQueueUrl())
                    .waitTimeSeconds(consumerProperties.waitTimeSeconds())
                    .maxNumberOfMessages(consumerProperties.maxMessages())
                    .messageSystemAttributeNames(
                            MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                            MessageSystemAttributeName.SENT_TIMESTAMP,
                            MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP)
                    .build());
            Instant receivedAt = Instant.now();
            long receivedNanos = System.nanoTime();
            // CallerRunsPolicy가 poller에서 처리하더라도 배치 전체의 실제 수신 시각을 먼저 기록합니다.
            response.messages().forEach(message -> logReceived(message, receivedAt));
            response.messages().forEach(message -> taskExecutor.execute(
                    () -> process(message, receivedAt, receivedNanos)));
        } catch (RuntimeException exception) {
            log.warn("travel_generation_result_receive_failed", exception);
        }
    }

    private void logReceived(Message message, Instant receivedAt) {
        long sentAt = timestamp(message, MessageSystemAttributeName.SENT_TIMESTAMP);
        long firstReceivedAt = timestamp(message, MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP);
        log.info("travel_generation_result_received queue_url={} sqs_message_id={} receive_count={} received_at={} sent_timestamp_ms={} first_receive_timestamp_ms={} sent_to_received_ms={} body_bytes={}",
                sqsProperties.resultQueueUrl(), message.messageId(), receiveCount(message), receivedAt,
                sentAt, firstReceivedAt,
                sentAt >= 0 && receivedAt.toEpochMilli() >= sentAt ? receivedAt.toEpochMilli() - sentAt : -1,
                message.body() == null ? 0 : message.body().getBytes(StandardCharsets.UTF_8).length);
    }

    private long timestamp(Message message, MessageSystemAttributeName attribute) {
        String value = message.attributes().get(attribute);
        if (value == null) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void process(Message sqsMessage, Instant receivedAt, long receivedNanos) {
        int receiveCount = receiveCount(sqsMessage);
        double executorWaitMillis = TravelGenerationResultTiming.elapsedMillis(receivedNanos);
        log.info("travel_generation_result_processing_started sqs_message_id={} receive_count={} received_at={} executor_wait_ms={}",
                sqsMessage.messageId(), receiveCount, receivedAt, executorWaitMillis);
        TravelGenerationResultMessage resultMessage;
        long parseStarted = System.nanoTime();
        try {
            resultMessage = objectMapper.readValue(sqsMessage.body(), TravelGenerationResultMessage.class);
            if (resultMessage == null) {
                throw new IllegalArgumentException("Result 메시지는 null일 수 없습니다.");
            }
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn(
                    "travel_generation_result_parse_failed sqs_message_id={} receive_count={} parse_ms={}",
                    sqsMessage.messageId(), receiveCount, TravelGenerationResultTiming.elapsedMillis(parseStarted),
                    exception
            );
            handleExhaustedFailure(sqsMessage, receiveCount, null, exception);
            return;
        }

        log.info("travel_generation_result_parsed sqs_message_id={} event_id={} job_id={} type={} receive_count={} executor_wait_ms={} parse_ms={}",
                sqsMessage.messageId(), resultMessage.eventId(), resultMessage.generationJobId(), resultMessage.type(),
                receiveCount, executorWaitMillis, TravelGenerationResultTiming.elapsedMillis(parseStarted));
        TravelGenerationResultTiming timing = TravelGenerationResultTiming.of(
                resultMessage, sqsMessage.messageId(), receiveCount);
        try {
            // Spring 트랜잭션 프록시가 반환할 때까지 측정하므로 커밋도 이 구간에 포함됩니다.
            TravelGenerationResultInboxService.ProcessingResult result = timing.measure("transaction", () -> inboxService.process(
                    resultMessage,
                    sqsMessage.messageId(),
                    sqsMessage.body(),
                    receiveCount
            ));
            delete(sqsMessage, resultMessage);
            log.info("travel_generation_result_processing_finished sqs_message_id={} event_id={} job_id={} receive_count={} result={} receive_to_delete_ms={}",
                    sqsMessage.messageId(), resultMessage.eventId(), resultMessage.generationJobId(), receiveCount,
                    result, TravelGenerationResultTiming.elapsedMillis(receivedNanos));
            if (result == TravelGenerationResultInboxService.ProcessingResult.DUPLICATE) {
                log.info(
                        "travel_generation_result_duplicate event_id={} job_id={}",
                        resultMessage.eventId(),
                        resultMessage.generationJobId()
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "travel_generation_result_process_failed sqs_message_id={} event_id={} job_id={} receive_count={} receive_to_failure_ms={}",
                    sqsMessage.messageId(),
                    resultMessage.eventId(),
                    resultMessage.generationJobId(),
                    receiveCount,
                    TravelGenerationResultTiming.elapsedMillis(receivedNanos),
                    exception
            );
            handleExhaustedFailure(sqsMessage, receiveCount, resultMessage, exception);
        }
    }

    private void handleExhaustedFailure(
            Message sqsMessage,
            int receiveCount,
            TravelGenerationResultMessage resultMessage,
            Throwable cause
    ) {
        if (receiveCount < consumerProperties.maxReceiveCount()) {
            return;
        }
        try {
            inboxService.archiveFailure(
                    sqsMessage.messageId(),
                    sqsMessage.body(),
                    receiveCount,
                    resultMessage,
                    cause
            );
            delete(sqsMessage, resultMessage);
            log.error(
                    "travel_generation_result_archived sqs_message_id={} event_id={} receive_count={}",
                    sqsMessage.messageId(),
                    resultMessage == null ? null : resultMessage.eventId(),
                    receiveCount
            );
        } catch (RuntimeException archiveException) {
            log.error(
                    "travel_generation_result_archive_failed sqs_message_id={} receive_count={}",
                    sqsMessage.messageId(),
                    receiveCount,
                    archiveException
            );
        }
    }

    private int receiveCount(Message message) {
        String value = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(value), 1);
        } catch (NumberFormatException exception) {
            log.warn(
                    "travel_generation_result_invalid_receive_count sqs_message_id={} value={}",
                    message.messageId(),
                    value
            );
            return 1;
        }
    }

    private void delete(Message sqsMessage, TravelGenerationResultMessage resultMessage) {
        TravelGenerationResultTiming timing = resultMessage == null
                ? new TravelGenerationResultTiming(sqsMessage.messageId(), null, null, receiveCount(sqsMessage))
                : TravelGenerationResultTiming.of(resultMessage, sqsMessage.messageId(), receiveCount(sqsMessage));
        timing.measure("delete", () -> sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(sqsProperties.resultQueueUrl())
                .receiptHandle(sqsMessage.receiptHandle())
                .build()));
        log.info(
                "travel_generation_result_consumed sqs_message_id={} event_id={} job_id={} type={}",
                sqsMessage.messageId(),
                resultMessage == null ? null : resultMessage.eventId(),
                resultMessage == null ? null : resultMessage.generationJobId(),
                resultMessage == null ? null : resultMessage.type()
        );
    }
}
