package com.audigo.domain.travel.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.global.config.JacksonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(JacksonConfig.class)
class TravelGenerationMessageTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void Job_메시지는_AI_요청_전체를_포함해_직렬화되고_복원된다() throws Exception {
        TravelGenerationJobMessage message = new TravelGenerationJobMessage(
                "event-job-1",
                41L,
                TravelGenerationMessageVersion.V1,
                Instant.parse("2026-10-16T01:00:00Z"),
                request()
        );

        String json = objectMapper.writeValueAsString(message);
        TravelGenerationJobMessage restored = objectMapper.readValue(json, TravelGenerationJobMessage.class);

        assertThat(objectMapper.readTree(json).path("generation_job_id").asLong()).isEqualTo(41L);
        assertThat(objectMapper.readTree(json).path("request").path("required_places")).hasSize(1);
        assertThat(restored).isEqualTo(message);
    }

    @Test
    void Job_메시지의_일시와_발생시각은_ISO_문자열로_전달된다() throws Exception {
        TravelGenerationJobMessage message = new TravelGenerationJobMessage(
                "event-job-iso",
                41L,
                TravelGenerationMessageVersion.V1,
                Instant.parse("2026-10-16T01:00:00Z"),
                request()
        );

        var json = objectMapper.readTree(objectMapper.writeValueAsString(message));
        var arrival = json.path("request").path("arrival_datetime");
        var departure = json.path("request").path("departure_datetime");

        assertThat(arrival.isTextual()).isTrue();
        assertThat(arrival.textValue()).isEqualTo("2026-10-20T10:00:00");
        assertThat(departure.isTextual()).isTrue();
        assertThat(departure.textValue()).isEqualTo("2026-10-22T18:00:00");
        assertThat(json.path("occurred_at").isTextual()).isTrue();
        assertThat(json.path("occurred_at").textValue()).isEqualTo("2026-10-16T01:00:00Z");
    }

    @Test
    void Result_메시지는_진행_완료_실패_계약을_구분한다() throws Exception {
        var payload = objectMapper.readTree("{\"days\":[{\"day_number\":1}]}");
        TravelGenerationResultMessage message = new TravelGenerationResultMessage(
                "event-result-1",
                41L,
                TravelGenerationMessageVersion.V1,
                Instant.parse("2026-10-16T01:05:00Z"),
                TravelGenerationResultType.COMPLETED,
                TravelGenerationStage.ROUTE_OPTIMIZE,
                TravelGenerationStageState.DONE,
                payload,
                null
        );

        String json = objectMapper.writeValueAsString(message);
        TravelGenerationResultMessage restored =
                objectMapper.readValue(json, TravelGenerationResultMessage.class);

        assertThat(objectMapper.readTree(json).path("type").asText()).isEqualTo("COMPLETED");
        assertThat(objectMapper.readTree(json).path("payload").path("days")).hasSize(1);
        assertThat(restored).isEqualTo(message);
    }

    private AiTravelGenerationRequest request() {
        return new AiTravelGenerationRequest(
                10L,
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
