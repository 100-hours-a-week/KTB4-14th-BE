package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.TravelGenerationResultInbox;
import com.audigo.domain.travel.entity.TravelGenerationResultInboxStatus;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.messaging.TravelGenerationMessageVersion;
import com.audigo.domain.travel.messaging.TravelGenerationResultMessage;
import com.audigo.domain.travel.messaging.TravelGenerationResultType;
import com.audigo.domain.travel.repository.TravelGenerationResultInboxRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelGenerationResultInboxServiceTest {

    @Mock
    private TravelGenerationResultInboxRepository inboxRepository;
    @Mock
    private TravelGenerationJobService jobService;

    private TravelGenerationResultInboxService service;

    @BeforeEach
    void setUp() {
        service = new TravelGenerationResultInboxService(inboxRepository, jobService);
    }

    @Test
    void 최초_eventId는_Inbox에_예약하고_결과를_한_번만_처리한다() {
        TravelGenerationResultMessage message = progressMessage();
        when(inboxRepository.findByEventId("event-1")).thenReturn(Optional.empty());
        when(inboxRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TravelGenerationResultInboxService.ProcessingResult result = service.process(
                message,
                "sqs-message-1",
                "{\"event_id\":\"event-1\"}",
                1
        );

        assertThat(result).isEqualTo(TravelGenerationResultInboxService.ProcessingResult.PROCESSED);
        ArgumentCaptor<TravelGenerationResultInbox> captor =
                ArgumentCaptor.forClass(TravelGenerationResultInbox.class);
        verify(inboxRepository).saveAndFlush(captor.capture());
        verify(jobService).handleResultMessage(message);
        assertThat(captor.getValue().getStatus())
                .isEqualTo(TravelGenerationResultInboxStatus.PROCESSED);
    }

    @Test
    void 이미_기록된_eventId는_결과와_알림을_다시_처리하지_않는다() {
        TravelGenerationResultInbox existing = org.mockito.Mockito.mock(TravelGenerationResultInbox.class);
        when(inboxRepository.findByEventId("event-1")).thenReturn(Optional.of(existing));

        TravelGenerationResultInboxService.ProcessingResult result = service.process(
                progressMessage(),
                "sqs-message-2",
                "{\"event_id\":\"event-1\"}",
                2
        );

        assertThat(result).isEqualTo(TravelGenerationResultInboxService.ProcessingResult.DUPLICATE);
        verify(inboxRepository, never()).saveAndFlush(any());
        verify(jobService, never()).handleResultMessage(any());
    }

    @Test
    void 최대_재시도_실패는_원문을_보관하고_작업을_실패시킨다() {
        TravelGenerationResultMessage message = progressMessage();
        when(inboxRepository.existsBySqsMessageId("sqs-message-1")).thenReturn(false);
        when(inboxRepository.findByEventId("event-1")).thenReturn(Optional.empty());

        service.archiveFailure(
                "sqs-message-1",
                "{\"event_id\":\"event-1\"}",
                5,
                message,
                new IllegalStateException("DB unavailable")
        );

        ArgumentCaptor<TravelGenerationResultInbox> captor =
                ArgumentCaptor.forClass(TravelGenerationResultInbox.class);
        verify(inboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TravelGenerationResultInboxStatus.FAILED);
        assertThat(captor.getValue().getReceiveCount()).isEqualTo(5);
        assertThat(captor.getValue().getLastError()).contains("DB unavailable");
        verify(jobService).markFailedIfPresent(
                41L,
                "여행 생성 결과 처리 재시도 횟수를 초과했습니다."
        );
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
