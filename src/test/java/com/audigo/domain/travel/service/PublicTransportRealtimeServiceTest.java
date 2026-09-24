package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class PublicTransportRealtimeServiceTest {

    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 9, 22, 12, 0);

    @Test
    void 대중교통_경로만_완료시각과_노선정보를_전달하고_응답을_갱신한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getMethod()).isEqualTo(org.springframework.http.HttpMethod.GET);
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("line")).isEqualTo("2%ED%98%B8%EC%84%A0");
                    assertThat(query.getFirst("vehicle")).isEqualTo("%EB%82%B4%EC%84%A0");
                    assertThat(query.getFirst("at")).isEqualTo("2026-09-22T12:00");
                })
                .andRespond(withSuccess(
                        """
                        {
                          "lineName": "2호선",
                          "vehicle_number": "내선",
                          "arrival_minutes": 3,
                          "estimatedDepartureAt": "2026-09-22T12:00:00",
                          "estimated_arrival_at": "2026-09-22T12:35:00"
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        store.putRoute(10L, new TravelItineraryMetadataStore.RouteMetadata(
                "2호선", "내선", 8, null,
                LocalDateTime.of(2026, 9, 22, 12, 35), false, null));
        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                builder, store, "http://transit.test/realtime");

        service.refresh(route(10L, TravelTransportType.PUBLIC_TRANSPORT), COMPLETED_AT);

        TravelItineraryMetadataStore.RouteMetadata refreshed = store.route(10L);
        assertThat(refreshed.nextArrivalMinutes()).isEqualTo(3);
        assertThat(refreshed.estimatedDepartureAt()).isEqualTo(COMPLETED_AT);
        assertThat(refreshed.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 12, 35));
        assertThat(refreshed.realtime()).isTrue();
        assertThat(refreshed.lastRefreshedAt()).isNotNull();
        server.verify();
    }

    @Test
    void 대중교통_API가_실패하면_AI_계산값을_유지하고_realtime을_켜지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://transit.test/realtime?line=2%ED%98%B8%EC%84%A0&vehicle=%EB%82%B4%EC%84%A0&at=2026-09-22T12:00"))
                .andRespond(withServerError());

        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        TravelItineraryMetadataStore.RouteMetadata initial =
                new TravelItineraryMetadataStore.RouteMetadata(
                        "2호선", "내선", 8,
                        null,
                        LocalDateTime.of(2026, 9, 22, 12, 35),
                        false,
                        null
                );
        store.putRoute(10L, initial);
        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                builder, store, "http://transit.test/realtime");

        service.refresh(route(10L, TravelTransportType.PUBLIC_TRANSPORT), COMPLETED_AT);

        assertThat(store.route(10L)).isEqualTo(initial);
        server.verify();
    }

    @Test
    void 도보와_자동차_경로는_외부_API를_호출하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                builder, store, "http://transit.test/realtime");

        service.refresh(route(11L, TravelTransportType.WALK), COMPLETED_AT);
        service.refresh(route(12L, TravelTransportType.CAR), COMPLETED_AT);

        assertThat(store.route(11L)).isNull();
        assertThat(store.route(12L)).isNull();
        server.verify();
    }

    private RouteSegment route(Long id, TravelTransportType transportType) {
        RouteSegment route = mock(RouteSegment.class);
        when(route.getId()).thenReturn(id);
        when(route.getTransportType()).thenReturn(transportType);
        return route;
    }
}
