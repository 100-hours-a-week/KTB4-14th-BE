package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.dto.BusArrivalResponse;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusArrivalQueryServiceTest {

    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 9, 29, 14, 0);

    @Mock
    private BusArrivalRealtimeService busArrivalRealtimeService;

    @Mock
    private TagoBusStopLookupService tagoBusStopLookupService;

    @Mock
    private RouteSegment route;

    @Mock
    private RouteSegmentLeg busLeg;

    @Mock
    private RouteSegmentLeg walkLeg;

    @Test
    void TAGO_식별자가_없으면_BUS_leg별_정류장_후보를_조회하고_WALK는_건너뛴다() {
        when(route.getId()).thenReturn(201L);
        when(route.getLegs()).thenReturn(List.of(busLeg, walkLeg));
        when(busLeg.getMode()).thenReturn("BUS");
        when(walkLeg.getMode()).thenReturn("WALK");
        when(tagoBusStopLookupService.findCandidates(route, busLeg)).thenReturn(List.of(
                new TagoBusStopLookupService.TagoStopIdentifier("25", "DJB8002544")
        ));
        when(busArrivalRealtimeService.findForLeg(
                eq(route), eq(busLeg), eq("25"), eq("DJB8002544"), eq(FETCHED_AT)
        )).thenReturn(new BusArrivalLookupResponse(
                201L,
                true,
                List.of(new BusArrivalResponse(
                        1, "115", 7, FETCHED_AT.plusMinutes(7), 3, "TAGO", FETCHED_AT
                )),
                FETCHED_AT
        ));

        BusArrivalLookupResponse response = new BusArrivalQueryService(
                busArrivalRealtimeService,
                tagoBusStopLookupService
        ).findForRoute(route, null, null, FETCHED_AT);

        assertThat(response.available()).isTrue();
        assertThat(response.arrivals()).hasSize(1);
        assertThat(response.arrivals().get(0).routeNumber()).isEqualTo("115");
        verify(tagoBusStopLookupService).findCandidates(route, busLeg);
        verify(tagoBusStopLookupService, never()).findCandidates(route, walkLeg);
        verify(busArrivalRealtimeService).findForLeg(
                eq(route), eq(busLeg), eq("25"), eq("DJB8002544"), eq(FETCHED_AT)
        );
    }

    @Test
    void TAGO_식별자가_전달되면_기존_노선_조회_로직을_재사용한다() {
        when(route.getId()).thenReturn(201L);
        BusArrivalLookupResponse expected = new BusArrivalLookupResponse(
                201L,
                true,
                List.of(new BusArrivalResponse(
                        1, "115", 7, FETCHED_AT.plusMinutes(7), 3, "TAGO", FETCHED_AT
                )),
                FETCHED_AT
        );
        when(busArrivalRealtimeService.findForRoute(route, "25", "DJB8002544", FETCHED_AT))
                .thenReturn(expected);

        BusArrivalLookupResponse response = new BusArrivalQueryService(
                busArrivalRealtimeService,
                tagoBusStopLookupService
        ).findForRoute(route, "25", "DJB8002544", FETCHED_AT);

        assertThat(response).isSameAs(expected);
        verify(busArrivalRealtimeService).findForRoute(route, "25", "DJB8002544", FETCHED_AT);
        verify(tagoBusStopLookupService, never()).findCandidates(any(), any());
    }

    @Test
    void 정류장_후보가_없으면_불가_응답을_반환한다() {
        when(route.getId()).thenReturn(201L);
        when(route.getLegs()).thenReturn(List.of(busLeg));
        when(busLeg.getMode()).thenReturn("BUS");
        when(tagoBusStopLookupService.findCandidates(route, busLeg)).thenReturn(List.of());

        BusArrivalLookupResponse response = new BusArrivalQueryService(
                busArrivalRealtimeService,
                tagoBusStopLookupService
        ).findForRoute(route, null, null, FETCHED_AT);

        assertThat(response.available()).isFalse();
        assertThat(response.arrivals()).isEmpty();
        assertThat(response.lastRefreshedAt()).isNull();
        verify(busArrivalRealtimeService, never()).findForLeg(any(), any(), any(), any(), any());
    }

    @Test
    void 정류장_후보_조회가_실패해도_예외를_전파하지_않고_불가_응답을_반환한다() {
        when(route.getId()).thenReturn(201L);
        when(route.getLegs()).thenReturn(List.of(busLeg));
        when(busLeg.getMode()).thenReturn("BUS");
        when(tagoBusStopLookupService.findCandidates(route, busLeg))
                .thenThrow(new RuntimeException("TAGO stop lookup failed"));

        BusArrivalLookupResponse response = new BusArrivalQueryService(
                busArrivalRealtimeService,
                tagoBusStopLookupService
        ).findForRoute(route, null, null, FETCHED_AT);

        assertThat(response.available()).isFalse();
        assertThat(response.arrivals()).isEmpty();
        assertThat(response.lastRefreshedAt()).isNull();
        verify(busArrivalRealtimeService, never()).findForLeg(any(), any(), any(), any(), any());
    }

    @Test
    void 버스_도착정보_조회가_예외를_발생해도_불가_응답을_반환한다() {
        when(route.getId()).thenReturn(201L);
        when(route.getLegs()).thenReturn(List.of(busLeg));
        when(busLeg.getMode()).thenReturn("BUS");
        when(tagoBusStopLookupService.findCandidates(route, busLeg)).thenReturn(List.of(
                new TagoBusStopLookupService.TagoStopIdentifier("25", "DJB8002544")
        ));
        when(busArrivalRealtimeService.findForLeg(
                eq(route), eq(busLeg), eq("25"), eq("DJB8002544"), eq(FETCHED_AT)
        )).thenThrow(new RuntimeException("TAGO arrival lookup failed"));

        BusArrivalLookupResponse response = new BusArrivalQueryService(
                busArrivalRealtimeService,
                tagoBusStopLookupService
        ).findForRoute(route, null, null, FETCHED_AT);

        assertThat(response.available()).isFalse();
        assertThat(response.arrivals()).isEmpty();
        assertThat(response.lastRefreshedAt()).isNull();
    }
}
