package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationPayload;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.domain.travel.repository.TravelGenerationPayloadRepository;
import com.audigo.global.config.JacksonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(JacksonConfig.class)
class TravelGenerationPayloadStoreTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 요청과_결과는_새로운_저장소_인스턴스에서도_jobId로_복원된다() throws Exception {
        TravelGenerationPayloadRepository payloadRepository = mock(TravelGenerationPayloadRepository.class);
        TravelGenerationJobRepository jobRepository = mock(TravelGenerationJobRepository.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        AtomicReference<TravelGenerationPayload> persisted = new AtomicReference<>();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(payloadRepository.findByJobId(1L))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(payloadRepository.save(any())).thenAnswer(invocation -> {
            TravelGenerationPayload payload = invocation.getArgument(0);
            persisted.set(payload);
            return payload;
        });

        var request = request();
        var writer = new TravelGenerationPayloadStore(payloadRepository, jobRepository, objectMapper);
        writer.saveRequest(1L, request);
        writer.saveResult(1L, "{\"days\":[]}");

        var savedRequest = objectMapper.readTree(persisted.get().getRequestPayload());
        assertThat(savedRequest.path("arrival_datetime").isTextual()).isTrue();
        assertThat(savedRequest.path("arrival_datetime").textValue()).isEqualTo("2026-10-20T10:00:00");
        assertThat(savedRequest.path("departure_datetime").isTextual()).isTrue();
        assertThat(savedRequest.path("departure_datetime").textValue()).isEqualTo("2026-10-22T18:00:00");

        var restored = new TravelGenerationPayloadStore(payloadRepository, jobRepository, objectMapper);

        assertThat(restored.getRequest(1L)).isEqualTo(request);
        assertThat(restored.getResult(1L)).isEqualTo("{\"days\":[]}");
    }

    @Test
    void 기존_배열_일시로_저장된_요청도_복원된다() throws Exception {
        TravelGenerationPayloadRepository payloadRepository = mock(TravelGenerationPayloadRepository.class);
        TravelGenerationJobRepository jobRepository = mock(TravelGenerationJobRepository.class);
        var request = request();
        ObjectNode legacyRequest = objectMapper.valueToTree(request);
        legacyRequest.putArray("arrival_datetime")
                .add(2026).add(10).add(20).add(10).add(0);
        legacyRequest.putArray("departure_datetime")
                .add(2026).add(10).add(22).add(18).add(0);
        TravelGenerationPayload payload = TravelGenerationPayload.create(
                mock(TravelGenerationJob.class),
                objectMapper.writeValueAsString(legacyRequest)
        );
        when(payloadRepository.findByJobId(1L)).thenReturn(Optional.of(payload));
        var store = new TravelGenerationPayloadStore(payloadRepository, jobRepository, objectMapper);

        assertThat(store.getRequest(1L)).isEqualTo(request);
    }

    private AiTravelGenerationRequest request() {
        return new AiTravelGenerationRequest(
                55L,
                1L,
                "서울특별시",
                LocalDateTime.of(2026, 10, 20, 10, 0),
                LocalDateTime.of(2026, 10, 22, 18, 0),
                2,
                "FRIEND",
                new AiTravelGenerationRequest.Preference(
                        TravelPaceType.BALANCED,
                        TravelTransportType.PUBLIC_TRANSPORT,
                        100000,
                        500000,
                        "KRW",
                        50,
                        List.of("NATURE"),
                        List.of("KOREAN"),
                        "천천히 둘러보고 싶어요."
                ),
                List.of(new AiTravelGenerationRequest.PlaceContext(
                        "KAKAO",
                        "place-1",
                        "경복궁",
                        "서울특별시 종로구 사직로 161",
                        new BigDecimal("37.579617"),
                        new BigDecimal("126.977041"),
                        "TOURISM",
                        1
                ))
        );
    }
}
