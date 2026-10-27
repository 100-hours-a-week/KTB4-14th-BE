package com.audigo.domain.travel.service;

import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Result 처리의 경과 시간을 측정합니다. 중첩된 구간의 시간은 합산하지 않습니다. */
record TravelGenerationResultTiming(String sqsMessageId, String eventId, Long jobId, Integer receiveCount) {

    private static final Logger log = LoggerFactory.getLogger(TravelGenerationResultTiming.class);

    static TravelGenerationResultTiming of(TravelGenerationResultMessage message, String sqsMessageId,
                                          Integer receiveCount) {
        return new TravelGenerationResultTiming(
                sqsMessageId, message.eventId(), message.generationJobId(), receiveCount);
    }

    <T> T measure(String stage, Supplier<T> operation) {
        long started = System.nanoTime();
        started(stage);
        String outcome = "SUCCESS";
        String errorType = "-";
        try {
            return operation.get();
        } catch (RuntimeException | Error exception) {
            outcome = "FAILURE";
            errorType = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            finished(stage, elapsedMillis(started), outcome, errorType);
        }
    }

    void observeCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private long commitStarted;

            @Override
            public void beforeCommit(boolean readOnly) {
                commitStarted = System.nanoTime();
                started("commit_phase");
            }

            @Override
            public void afterCompletion(int status) {
                String outcome = switch (status) {
                    case STATUS_COMMITTED -> "COMMITTED";
                    case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                    default -> "UNKNOWN";
                };
                // beforeCommit 전에 롤백됐다면 커밋 구간이 없으므로 -1로 구분합니다.
                finished("commit_phase", commitStarted == 0 ? -1 : elapsedMillis(commitStarted), outcome, "-");
            }
        });
    }

    private void started(String stage) {
        log.info("travel_generation_result_stage_started stage={} sqs_message_id={} event_id={} job_id={} receive_count={}",
                stage, sqsMessageId, eventId, jobId, receiveCount);
    }

    private void finished(String stage, double durationMillis, String outcome, String errorType) {
        log.info("travel_generation_result_stage_finished stage={} sqs_message_id={} event_id={} job_id={} receive_count={} duration_ms={} outcome={} error_type={}",
                stage, sqsMessageId, eventId, jobId, receiveCount, durationMillis, outcome, errorType);
    }

    static double elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }
}
