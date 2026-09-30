package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusArrivalRealtimeServiceTest {

    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 9, 28, 14, 0);

    @Test
    void 정류장_검증없이_전달받은_TAGO_식별자로_노선별_가장_빠른_도착정보를_반환한다() {
        TagoBusArrivalClient client = mock(TagoBusArrivalClient.class);
        RouteSegment route = routeWithBusLeg();
        when(client.findArrivals("25", "node-1")).thenReturn(List.of(
                new TagoBusArrivalClient.Arrival("route-643", "643", 480, 3, "일반"),
                new TagoBusArrivalClient.Arrival("route-643", "643", 900, 8, "일반"),
                new TagoBusArrivalClient.Arrival("route-506", "506", 60, 1, "저상"),
                new TagoBusArrivalClient.Arrival("route-999", "999", 30, 1, "일반")
        ));

        BusArrivalLookupResponse response = new BusArrivalRealtimeService(client)
                .findForRoute(route, "25", "node-1", FETCHED_AT);

        assertThat(response.available()).isTrue();
        assertThat(response.lastRefreshedAt()).isEqualTo(FETCHED_AT);
        assertThat(response.arrivals()).extracting("routeNumber")
                .containsExactly("506", "643");
        assertThat(response.arrivals()).extracting("nextArrivalMinutes")
                .containsExactly(1, 8);
        assertThat(response.arrivals().get(0).remainingStops()).isEqualTo(1);
        assertThat(response.arrivals().get(0).expectedArrivalAt())
                .isEqualTo(FETCHED_AT.plusSeconds(60));
        verify(client).findArrivals("25", "node-1");
    }

    @Test
    void 도시코드나_정류소ID가_없으면_외부_API를_호출하지_않는다() {
        TagoBusArrivalClient client = mock(TagoBusArrivalClient.class);
        RouteSegment route = routeWithBusLeg();

        BusArrivalLookupResponse response = new BusArrivalRealtimeService(client)
                .findForRoute(route, null, "node-1", FETCHED_AT);

        assertThat(response.available()).isFalse();
        assertThat(response.arrivals()).isEmpty();
        assertThat(response.lastRefreshedAt()).isNull();
        verify(client, never()).findArrivals("25", "node-1");
    }

    @Test
    void TAGO_API_실패시_일정_조회용_빈_응답을_반환한다() {
        TagoBusArrivalClient client = mock(TagoBusArrivalClient.class);
        RouteSegment route = routeWithBusLeg();
        when(client.findArrivals("25", "node-1"))
                .thenThrow(new TagoBusArrivalClient.TagoBusArrivalException("test failure"));

        BusArrivalLookupResponse response = new BusArrivalRealtimeService(client)
                .findForRoute(route, "25", "node-1", FETCHED_AT);

        assertThat(response.available()).isFalse();
        assertThat(response.arrivals()).isEmpty();
        assertThat(response.lastRefreshedAt()).isNull();
    }

    private RouteSegment routeWithBusLeg() {
        RouteSegment route = mock(RouteSegment.class);
        RouteSegmentLeg busLeg = mock(RouteSegmentLeg.class);
        when(route.getId()).thenReturn(41L);
        when(route.getTransportType()).thenReturn(TravelTransportType.PUBLIC_TRANSPORT);
        when(route.getLegs()).thenReturn(List.of(busLeg));
        when(busLeg.getSequence()).thenReturn(2);
        when(busLeg.getMode()).thenReturn("BUS");
        when(busLeg.getBusNumbers()).thenReturn(List.of("643", "506"));
        return route;
    }
}
