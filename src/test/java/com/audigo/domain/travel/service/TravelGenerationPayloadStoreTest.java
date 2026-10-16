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
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TravelGenerationPayloadStoreTest {

    @Test
    void 요청과_결과는_새로운_저장소_인스턴스에서도_jobId로_복원된다() {
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

        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        var request = request();
        var writer = new TravelGenerationPayloadStore(payloadRepository, jobRepository, objectMapper);
        writer.saveRequest(1L, request);
        writer.saveResult(1L, "{\"days\":[]}");

        var restored = new TravelGenerationPayloadStore(payloadRepository, jobRepository, objectMapper);

        assertThat(restored.getRequest(1L)).isEqualTo(request);
        assertThat(restored.getResult(1L)).isEqualTo("{\"days\":[]}");
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
