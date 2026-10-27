package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.TravelGenerationResultInbox;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.repository.TravelGenerationResultInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelGenerationResultInboxService {

    private static final String RETRY_EXHAUSTED_MESSAGE =
            "여행 생성 결과 처리 재시도 횟수를 초과했습니다.";

    private final TravelGenerationResultInboxRepository inboxRepository;
    private final TravelGenerationJobService jobService;

    public TravelGenerationResultInboxService(
            TravelGenerationResultInboxRepository inboxRepository,
            TravelGenerationJobService jobService
    ) {
        this.inboxRepository = inboxRepository;
        this.jobService = jobService;
    }

    @Transactional
    public ProcessingResult process(
            TravelGenerationResultMessage message,
            String sqsMessageId,
            String messageBody,
            int receiveCount
    ) {
        TravelGenerationResultTiming timing = TravelGenerationResultTiming.of(message, sqsMessageId, receiveCount);
        timing.observeCommit();
        if (timing.measure("inbox_lookup", () -> inboxRepository.findByEventId(message.eventId())).isPresent()) {
            return ProcessingResult.DUPLICATE;
        }

        TravelGenerationResultInbox inbox = TravelGenerationResultInbox.processing(
                message,
                sqsMessageId,
                messageBody,
                receiveCount
        );
        timing.measure("inbox_insert", () -> inboxRepository.saveAndFlush(inbox));
        timing.measure("result_apply", () -> {
            jobService.handleResultMessage(message);
            return null;
        });
        inbox.markProcessed();
        return ProcessingResult.PROCESSED;
    }

    @Transactional
    public void archiveFailure(
            String sqsMessageId,
            String messageBody,
            int receiveCount,
            TravelGenerationResultMessage message,
            Throwable cause
    ) {
        if (inboxRepository.existsBySqsMessageId(sqsMessageId)) {
            return;
        }
        if (message != null && inboxRepository.findByEventId(message.eventId()).isPresent()) {
            return;
        }

        inboxRepository.save(TravelGenerationResultInbox.failed(
                sqsMessageId,
                message == null ? null : message.eventId(),
                message == null ? null : message.generationJobId(),
                message == null ? null : message.type(),
                messageBody,
                receiveCount,
                errorMessage(cause)
        ));
        if (message != null) {
            jobService.markFailedIfPresent(message.generationJobId(), RETRY_EXHAUSTED_MESSAGE);
        }
    }

    private String errorMessage(Throwable cause) {
        if (cause == null) {
            return "여행 생성 Result 메시지 처리에 실패했습니다.";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    public enum ProcessingResult {
        PROCESSED,
        DUPLICATE
    }
}
