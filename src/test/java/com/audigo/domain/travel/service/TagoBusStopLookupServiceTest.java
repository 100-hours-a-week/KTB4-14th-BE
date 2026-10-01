package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelPlan;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TagoBusStopLookupServiceTest {

    private TagoBusStopClient client;
    private TagoBusStopLookupService service;
    private RouteSegment route;
    private RouteSegmentLeg leg;
    private Region region;

    @BeforeEach
    void setUp() {
        client = mock(TagoBusStopClient.class);
        service = new TagoBusStopLookupService(client);
        route = mock(RouteSegment.class);
        leg = mock(RouteSegmentLeg.class);
        TravelPlan plan = mock(TravelPlan.class);
        region = mock(Region.class);
        when(route.getTravelPlan()).thenReturn(plan);
        when(plan.getRegion()).thenReturn(region);
        when(region.getFullName()).thenReturn("대전광역시 서구");
        when(leg.getMode()).thenReturn("BUS");
        when(leg.getBoardingStopName()).thenReturn("서부소방서");
    }

    @Test
    void 지역에_맞는_도시코드로_TAGO_정류장_후보를_조회한다() {
        when(client.findCities()).thenReturn(List.of(
                new TagoBusStopClient.City("25", "대전광역시")
        ));
        when(client.findStopsByName("25", "서부소방서")).thenReturn(List.of(
                new TagoBusStopClient.Stop("25", "DJB8002544", "다른표기", "1234"),
                new TagoBusStopClient.Stop("25", "DJB8002545", "서부소방서", "1235")
        ));

        List<TagoBusStopLookupService.TagoStopIdentifier> result =
                service.findCandidates(route, leg);

        assertThat(result).containsExactly(
                new TagoBusStopLookupService.TagoStopIdentifier("25", "DJB8002544"),
                new TagoBusStopLookupService.TagoStopIdentifier("25", "DJB8002545")
        );
        verify(client).findStopsByName("25", "서부소방서");
    }

    @Test
    void 시군구_지역명으로_TAGO_도시코드를_조회한다() {
        when(region.getName()).thenReturn("수원시");
        when(region.getFullName()).thenReturn("경기도 수원시");
        when(leg.getBoardingStopName()).thenReturn("수원역");
        when(client.findCities()).thenReturn(List.of(
                new TagoBusStopClient.City("31010", "수원시")
        ));
        when(client.findStopsByName("31010", "수원역")).thenReturn(List.of(
                new TagoBusStopClient.Stop("31010", "SWA000001", "수원역", "1234")
        ));

        List<TagoBusStopLookupService.TagoStopIdentifier> result =
                service.findCandidates(route, leg);

        assertThat(result).containsExactly(
                new TagoBusStopLookupService.TagoStopIdentifier("31010", "SWA000001")
        );
        verify(client).findStopsByName("31010", "수원역");
    }

    @Test
    void WALK_leg은_TAGO_정류장_조회를_하지_않는다() {
        when(leg.getMode()).thenReturn("WALK");

        assertThat(service.findCandidates(route, leg)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void 지역명이나_탑승_정류장명이_없으면_빈_후보를_반환한다() {
        when(region.getFullName()).thenReturn("");

        assertThat(service.findCandidates(route, leg)).isEmpty();
        verifyNoInteractions(client);
    }
}
