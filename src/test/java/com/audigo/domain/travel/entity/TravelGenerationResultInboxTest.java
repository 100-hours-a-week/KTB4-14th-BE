package com.audigo.domain.travel.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.audigo.domain.travel.messaging.TravelGenerationMessageVersion;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.messaging.TravelGenerationResultType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TravelGenerationResultInboxTest {

    @Test
    void 처리_중인_Result_메시지를_완료_상태로_변경한다() {
        TravelGenerationResultInbox inbox = TravelGenerationResultInbox.processing(
                progressMessage(),
                "sqs-message-1",
                "{\"event_id\":\"event-1\"}",
                2
        );

        assertThat(inbox.getStatus()).isEqualTo(TravelGenerationResultInboxStatus.PROCESSING);
        assertThat(inbox.getEventId()).isEqualTo("event-1");
        assertThat(inbox.getGenerationJobId()).isEqualTo(41L);
        assertThat(inbox.getReceiveCount()).isEqualTo(2);

        inbox.markProcessed();

        assertThat(inbox.getStatus()).isEqualTo(TravelGenerationResultInboxStatus.PROCESSED);
        assertThat(inbox.getProcessedAt()).isNotNull();
    }

    @Test
    void 파싱하지_못한_Result_메시지도_원문과_오류를_보관한다() {
        TravelGenerationResultInbox inbox = TravelGenerationResultInbox.failed(
                "sqs-message-2",
                null,
                null,
                null,
                "not-json",
                5,
                "JsonProcessingException: invalid"
        );

        assertThat(inbox.getStatus()).isEqualTo(TravelGenerationResultInboxStatus.FAILED);
        assertThat(inbox.getEventId()).isNull();
        assertThat(inbox.getMessageBody()).isEqualTo("not-json");
        assertThat(inbox.getLastError()).contains("invalid");
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
}
