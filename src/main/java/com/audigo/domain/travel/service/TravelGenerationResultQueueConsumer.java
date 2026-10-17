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
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(prefix = "audigo.sqs", name = "enabled", havingValue = "true")
public class TravelGenerationResultQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationResultQueueConsumer.class);

    private final SqsClient sqsClient;
    private final TravelSqsProperties sqsProperties;
    private final TravelResultQueueProperties consumerProperties;
    private final ObjectMapper objectMapper;
    private final TravelGenerationJobService jobService;
    private final TaskExecutor taskExecutor;

    public TravelGenerationResultQueueConsumer(
            SqsClient sqsClient,
            TravelSqsProperties sqsProperties,
            TravelResultQueueProperties consumerProperties,
            ObjectMapper objectMapper,
            TravelGenerationJobService jobService,
            @Qualifier("travelResultQueueExecutor") TaskExecutor taskExecutor
    ) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.consumerProperties = consumerProperties;
        this.objectMapper = objectMapper;
        this.jobService = jobService;
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
                    .build());
            response.messages().forEach(message -> taskExecutor.execute(() -> process(message)));
        } catch (RuntimeException exception) {
            log.warn("travel_generation_result_receive_failed", exception);
        }
    }

    private void process(Message sqsMessage) {
        TravelGenerationResultMessage resultMessage;
        try {
            resultMessage = objectMapper.readValue(sqsMessage.body(), TravelGenerationResultMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn(
                    "travel_generation_result_parse_failed sqs_message_id={}",
                    sqsMessage.messageId(),
                    exception
            );
            return;
        }

        try {
            jobService.handleResultMessage(resultMessage);
            delete(sqsMessage, resultMessage);
        } catch (RuntimeException exception) {
            log.warn(
                    "travel_generation_result_process_failed sqs_message_id={} event_id={} job_id={}",
                    sqsMessage.messageId(),
                    resultMessage.eventId(),
                    resultMessage.generationJobId(),
                    exception
            );
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
                resultMessage.eventId(),
                resultMessage.generationJobId(),
                resultMessage.type()
        );
    }
}
