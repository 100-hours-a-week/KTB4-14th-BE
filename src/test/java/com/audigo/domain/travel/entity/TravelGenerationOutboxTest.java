package com.audigo.domain.travel.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TravelGenerationOutboxTest {

    @Test
    void 실패해도_같은_이벤트와_payload로_재시도한다() {
        TravelGenerationOutbox outbox = TravelGenerationOutbox.create(
                mock(TravelGenerationJob.class),
                "event-1",
                "TRAVEL_GENERATION_REQUESTED",
                "{\"generation_job_id\":41}"
        );
        LocalDateTime claimedAt = LocalDateTime.of(2026, 10, 17, 1, 0);
        LocalDateTime retryAt = claimedAt.plusSeconds(5);

        outbox.claim(claimedAt);
        outbox.scheduleRetry(retryAt, "temporary failure");

        assertThat(outbox.getStatus()).isEqualTo(TravelGenerationOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(outbox.getEventId()).isEqualTo("event-1");
        assertThat(outbox.getPayload()).isEqualTo("{\"generation_job_id\":41}");
    }

    @Test
    void 발행이_완료되면_완료_시각을_기록한다() {
        TravelGenerationOutbox outbox = TravelGenerationOutbox.create(
                mock(TravelGenerationJob.class),
                "event-1",
                "TRAVEL_GENERATION_REQUESTED",
                "{\"generation_job_id\":41}"
        );
        LocalDateTime publishedAt = LocalDateTime.of(2026, 10, 17, 1, 5);

        outbox.claim(publishedAt.minusSeconds(1));
        outbox.markPublished(publishedAt);

        assertThat(outbox.getStatus()).isEqualTo(TravelGenerationOutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(outbox.getLockedAt()).isNull();
    }
}
