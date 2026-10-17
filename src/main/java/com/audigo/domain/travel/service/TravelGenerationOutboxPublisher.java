package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TravelSqsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(prefix = "audigo.sqs", name = "enabled", havingValue = "true")
public class TravelGenerationOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationOutboxPublisher.class);

    private final TravelGenerationOutboxStore outboxStore;
    private final TravelSqsProperties sqsProperties;
    private final SqsClient sqsClient;

    public TravelGenerationOutboxPublisher(
            TravelGenerationOutboxStore outboxStore,
            TravelSqsProperties sqsProperties,
            SqsClient sqsClient
    ) {
        this.outboxStore = outboxStore;
        this.sqsProperties = sqsProperties;
        this.sqsClient = sqsClient;
    }

    @Scheduled(fixedDelayString = "${audigo.sqs.outbox.publisher-delay-millis:1000}")
    public void publishPendingEvents() {
        for (TravelGenerationOutboxStore.ClaimedEvent event : outboxStore.claimReadyEvents()) {
            publish(event);
        }
    }

    private void publish(TravelGenerationOutboxStore.ClaimedEvent event) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(sqsProperties.jobQueueUrl())
                    .messageBody(event.payload())
                    .build());
            outboxStore.markPublished(event.id());
            log.info(
                    "travel_generation_outbox_published outbox_id={} event_id={} attempt={}",
                    event.id(),
                    event.eventId(),
                    event.attemptCount() + 1
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "travel_generation_outbox_publish_failed outbox_id={} event_id={} attempt={}",
                    event.id(),
                    event.eventId(),
                    event.attemptCount() + 1,
                    exception
            );
            outboxStore.markForRetry(event.id(), exception.getMessage());
        }
    }
}
