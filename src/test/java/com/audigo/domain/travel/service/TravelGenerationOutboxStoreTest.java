package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.config.TravelOutboxProperties;
import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationOutbox;
import com.audigo.domain.travel.messaging.TravelGenerationJobMessage;
import com.audigo.domain.travel.repository.TravelGenerationOutboxRepository;
import com.audigo.global.config.JacksonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(JacksonConfig.class)
class TravelGenerationOutboxStoreTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 최초_Job_메시지_JSON을_Outbox에_그대로_저장한다() throws Exception {
        TravelGenerationOutboxRepository repository = mock(TravelGenerationOutboxRepository.class);
        TravelGenerationOutboxStore store = new TravelGenerationOutboxStore(
                repository,
                new TravelOutboxProperties(10, 5, 300, 120),
                objectMapper
        );
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        TravelGenerationJobMessage message = new TravelGenerationJobMessage(
                "event-1",
                41L,
                "1",
                Instant.parse("2026-10-17T01:00:00Z"),
                new AiTravelGenerationRequest(
                        10L,
                        1L,
                        "서울특별시",
                        LocalDateTime.of(2026, 10, 20, 10, 0),
                        LocalDateTime.of(2026, 10, 22, 18, 0),
                        2,
                        "FRIEND",
                        null,
                        List.of()
                )
        );

        store.enqueue(job, message);

        ArgumentCaptor<TravelGenerationOutbox> captor = ArgumentCaptor.forClass(TravelGenerationOutbox.class);
        verify(repository).save(captor.capture());
        TravelGenerationOutbox saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo("event-1");
        var savedRequest = objectMapper.readTree(saved.getPayload()).path("request");
        assertThat(savedRequest.path("arrival_datetime").isTextual()).isTrue();
        assertThat(savedRequest.path("arrival_datetime").textValue()).isEqualTo("2026-10-20T10:00:00");
        assertThat(savedRequest.path("departure_datetime").isTextual()).isTrue();
        assertThat(savedRequest.path("departure_datetime").textValue()).isEqualTo("2026-10-22T18:00:00");
        assertThat(objectMapper.readValue(saved.getPayload(), TravelGenerationJobMessage.class))
                .isEqualTo(message);
    }

    @Test
    void 발행할_이벤트를_조회하면_PROCESSING으로_선점한다() {
        TravelGenerationOutboxRepository repository = mock(TravelGenerationOutboxRepository.class);
        TravelGenerationOutboxStore store = new TravelGenerationOutboxStore(
                repository,
                new TravelOutboxProperties(10, 5, 300, 120),
                objectMapper
        );
        TravelGenerationOutbox event = TravelGenerationOutbox.create(
                mock(TravelGenerationJob.class),
                "event-1",
                TravelGenerationOutboxStore.EVENT_TYPE,
                "{}"
        );
        when(repository.findReadyForPublish(any(), any(), any(), any(), any()))
                .thenReturn(List.of(event));

        var claimed = store.claimReadyEvents();

        assertThat(claimed).singleElement().satisfies(value -> {
            assertThat(value.eventId()).isEqualTo("event-1");
            assertThat(value.payload()).isEqualTo("{}");
        });
        assertThat(event.getStatus().name()).isEqualTo("PROCESSING");
    }
}
