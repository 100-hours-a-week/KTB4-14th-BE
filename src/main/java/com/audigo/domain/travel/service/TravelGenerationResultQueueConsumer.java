package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TravelResultQueueProperties;
import com.audigo.domain.travel.config.TravelSqsProperties;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                    .build());
            response.messages().forEach(message -> taskExecutor.execute(() -> process(message)));
        } catch (RuntimeException exception) {
            log.warn("travel_generation_result_receive_failed", exception);
        }
    }

    private void process(Message sqsMessage) {
        int receiveCount = receiveCount(sqsMessage);
        TravelGenerationResultMessage resultMessage;
        try {
            resultMessage = objectMapper.readValue(sqsMessage.body(), TravelGenerationResultMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn(
                    "travel_generation_result_parse_failed sqs_message_id={}",
                    sqsMessage.messageId(),
                    exception
            );
            handleExhaustedFailure(sqsMessage, receiveCount, null, exception);
            return;
        }

        try {
            TravelGenerationResultInboxService.ProcessingResult result = inboxService.process(
                    resultMessage,
                    sqsMessage.messageId(),
                    sqsMessage.body(),
                    receiveCount
            );
            delete(sqsMessage, resultMessage);
            if (result == TravelGenerationResultInboxService.ProcessingResult.DUPLICATE) {
                log.info(
                        "travel_generation_result_duplicate event_id={} job_id={}",
                        resultMessage.eventId(),
                        resultMessage.generationJobId()
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "travel_generation_result_process_failed sqs_message_id={} event_id={} job_id={}",
                    sqsMessage.messageId(),
                    resultMessage.eventId(),
                    resultMessage.generationJobId(),
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
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(sqsProperties.resultQueueUrl())
                .receiptHandle(sqsMessage.receiptHandle())
                .build());
        log.info(
                "travel_generation_result_consumed sqs_message_id={} event_id={} job_id={} type={}",
                sqsMessage.messageId(),
                resultMessage == null ? null : resultMessage.eventId(),
                resultMessage == null ? null : resultMessage.generationJobId(),
                resultMessage == null ? null : resultMessage.type()
        );
    }
}
