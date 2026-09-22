package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.audigo.domain.travel.config.AiServerProperties;
import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiSseGenerationClientTest {

    @Test
    void sse_이벤트를_수신한_순서대로_전달한다() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/api/ai/v1/itinerary-jobs/stream"))
                .andRespond(withSuccess(
                        getClass().getResourceAsStream("/mock/ai/itinerary-stream.sse")
                                .readAllBytes(),
                        MediaType.TEXT_EVENT_STREAM));

        AiSseGenerationClient client = new AiSseGenerationClient(
                builder,
                new AiServerProperties("http://ai.test", "/api/ai/v1/itinerary-jobs/stream")
        );
        List<AiGenerationEvent> events = new ArrayList<>();

        client.stream(request(), events::add);

        assertThat(events).extracting(AiGenerationEvent::normalizedType)
                .containsExactly(
                        "PLACE_RECOMMEND_DONE",
                        "STAY_RECOMMEND_DONE",
                        "ROUTE_OPTIMIZE_DONE",
                        "COMPLETE"
                );
        assertThat(events.get(3).data()).contains("\"category\":\"관광\"");
        assertThat(events.get(3).data()).contains("route_from_previous");
        server.verify();
    }

    private AiTravelGenerationRequest request() {
        return new AiTravelGenerationRequest(
                1L,
                2L,
                "서울특별시",
                LocalDateTime.of(2026, 9, 22, 10, 0),
                LocalDateTime.of(2026, 9, 23, 18, 0),
                1,
                "SOLO",
                null,
                List.of()
        );
    }
}
