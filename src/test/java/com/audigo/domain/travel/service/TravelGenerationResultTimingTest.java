package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TravelGenerationResultTimingTest {

    private final TravelGenerationResultTiming timing =
            new TravelGenerationResultTiming("message-1", "event-1", 41L, 3);
    private final Logger logger = (Logger) LoggerFactory.getLogger(TravelGenerationResultTiming.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 실패한_구간의_예외를_그대로_전파하고_민감한_예외_메시지는_추가_로그에_넣지_않는다() {
        IllegalStateException failure = new IllegalStateException("private database credentials");

        assertThatThrownBy(() -> timing.measure("inbox_insert", () -> { throw failure; }))
                .isSameAs(failure);

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("stage=inbox_insert", "outcome=FAILURE", "error_type=IllegalStateException",
                        "sqs_message_id=message-1", "event_id=event-1", "job_id=41", "receive_count=3"));
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("private database credentials"));
    }

    @Test
    void 실제_트랜잭션이_없으면_커밋_성공_로그를_만들지_않는다() {
        timing.observeCommit();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void 메서드_본문이_끝나도_afterCompletion_전에는_커밋_성공으로_기록하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        timing.observeCommit();
        var synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThat(appender.list).isEmpty();
        synchronization.beforeCommit(false);
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("outcome=COMMITTED"));
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("stage=commit_phase", "outcome=COMMITTED", "duration_ms="));
    }

    @Test
    void 커밋_시작_전_롤백은_소요시간을_없는_값으로_구분한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        timing.observeCommit();

        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("stage=commit_phase", "outcome=ROLLED_BACK", "duration_ms=-1.0"));
    }
}
