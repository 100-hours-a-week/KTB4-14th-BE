package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicTransportRealtimeServiceTest {

    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 9, 22, 12, 0);

    @Test
    void 대중교통_경로의_출발도착좌표를_카카오에_전달하고_현재시각기준_예상시간을_갱신한다() {
        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        KakaoTransitCoordinateResolver coordinateResolver = mock(KakaoTransitCoordinateResolver.class);
        KakaoPublicTransitClient client = mock(KakaoPublicTransitClient.class);
        RouteSegment route = route(10L, TravelTransportType.PUBLIC_TRANSPORT, 101L, 102L);
        store.putRoute(10L, new TravelItineraryMetadataStore.RouteMetadata(
                "2호선", "내선", 8, null,
                LocalDateTime.of(2026, 9, 22, 12, 35), false, null));
        when(coordinateResolver.resolve(route)).thenReturn(Optional.of(coordinates()));
        when(client.findRoute(
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                eq(COMPLETED_AT),
                eq(KakaoPublicTransitClient.RouteType.ALL)
        )).thenReturn(new KakaoPublicTransitClient.RouteResult(
                "transit-1",
                COMPLETED_AT,
                COMPLETED_AT.plusSeconds(2_280),
                2_280,
                120,
                6_400,
                1
        ));

        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                store, coordinateResolver, client);

        service.refresh(route, COMPLETED_AT);

        TravelItineraryMetadataStore.RouteMetadata refreshed = store.route(10L);
        assertThat(refreshed.nextArrivalMinutes()).isEqualTo(38);
        assertThat(refreshed.estimatedDepartureAt()).isEqualTo(COMPLETED_AT);
        assertThat(refreshed.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 12, 38));
        assertThat(refreshed.realtime()).isTrue();
        assertThat(refreshed.lastRefreshedAt()).isNotNull();
        assertThat(refreshed.lineName()).isEqualTo("2호선");
        assertThat(refreshed.vehicleNumber()).isEqualTo("내선");
        verify(client).findRoute(
                eq(new BigDecimal("129.100")),
                eq(new BigDecimal("35.100")),
                eq(new BigDecimal("129.200")),
                eq(new BigDecimal("35.200")),
                eq(COMPLETED_AT),
                eq(KakaoPublicTransitClient.RouteType.ALL)
        );
    }

    @Test
    void 출발도착좌표가_없으면_카카오를_호출하지_않고_AI_계산값을_유지한다() {
        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        KakaoTransitCoordinateResolver coordinateResolver = mock(KakaoTransitCoordinateResolver.class);
        KakaoPublicTransitClient client = mock(KakaoPublicTransitClient.class);
        RouteSegment route = route(10L, TravelTransportType.PUBLIC_TRANSPORT, 101L, 102L);
        when(coordinateResolver.resolve(route)).thenReturn(Optional.empty());
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
                store, coordinateResolver, client);

        service.refresh(route, COMPLETED_AT);

        assertThat(store.route(10L)).isEqualTo(initial);
        verify(client, never()).findRoute(
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(LocalDateTime.class),
                any(KakaoPublicTransitClient.RouteType.class)
        );
    }

    @Test
    void 카카오_API가_실패하면_AI_계산값을_유지하고_realtime을_켜지_않는다() {
        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        KakaoTransitCoordinateResolver coordinateResolver = mock(KakaoTransitCoordinateResolver.class);
        KakaoPublicTransitClient client = mock(KakaoPublicTransitClient.class);
        RouteSegment route = route(10L, TravelTransportType.PUBLIC_TRANSPORT, 101L, 102L);
        when(coordinateResolver.resolve(route)).thenReturn(Optional.of(coordinates()));
        TravelItineraryMetadataStore.RouteMetadata initial =
                new TravelItineraryMetadataStore.RouteMetadata(
                        "2호선", "내선", 8,
                        null,
                        LocalDateTime.of(2026, 9, 22, 12, 35),
                        false,
                        null
                );
        store.putRoute(10L, initial);
        when(client.findRoute(
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(LocalDateTime.class),
                any(KakaoPublicTransitClient.RouteType.class)
        )).thenThrow(new KakaoPublicTransitClient.KakaoPublicTransitException("test failure"));

        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                store, coordinateResolver, client);

        service.refresh(route, COMPLETED_AT);

        assertThat(store.route(10L)).isEqualTo(initial);
    }

    @Test
    void 도보와_자동차_경로는_카카오를_호출하지_않는다() {
        TravelItineraryMetadataStore store = new TravelItineraryMetadataStore();
        KakaoTransitCoordinateResolver coordinateResolver = mock(KakaoTransitCoordinateResolver.class);
        KakaoPublicTransitClient client = mock(KakaoPublicTransitClient.class);
        PublicTransportRealtimeService service = new PublicTransportRealtimeService(
                store, coordinateResolver, client);

        service.refresh(route(11L, TravelTransportType.WALK, 101L, 102L), COMPLETED_AT);
        service.refresh(route(12L, TravelTransportType.CAR, 101L, 102L), COMPLETED_AT);

        assertThat(store.route(11L)).isNull();
        assertThat(store.route(12L)).isNull();
        verify(client, never()).findRoute(
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(LocalDateTime.class),
                any(KakaoPublicTransitClient.RouteType.class)
        );
    }

    private KakaoTransitCoordinateResolver.Coordinates coordinates() {
        return new KakaoTransitCoordinateResolver.Coordinates(
                new BigDecimal("129.100"),
                new BigDecimal("35.100"),
                new BigDecimal("129.200"),
                new BigDecimal("35.200")
        );
    }

    private RouteSegment route(Long id, TravelTransportType transportType, Long fromId, Long toId) {
        RouteSegment route = mock(RouteSegment.class);
        ItineraryItem from = mock(ItineraryItem.class);
        ItineraryItem to = mock(ItineraryItem.class);
        when(route.getId()).thenReturn(id);
        when(route.getTransportType()).thenReturn(transportType);
        when(route.getFromItineraryItem()).thenReturn(from);
        when(route.getToItineraryItem()).thenReturn(to);
        when(from.getId()).thenReturn(fromId);
        when(to.getId()).thenReturn(toId);
        return route;
    }
}
