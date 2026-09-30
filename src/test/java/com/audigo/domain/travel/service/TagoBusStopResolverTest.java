package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelPlan;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TagoBusStopResolverTest {

    private TagoBusStopClient client;
    private TagoBusStopResolver resolver;
    private RouteSegment route;
    private RouteSegmentLeg leg;
    private Region region;

    @BeforeEach
    void setUp() {
        client = mock(TagoBusStopClient.class);
        resolver = new TagoBusStopResolver(client);
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
    void 지역과_정류소명으로_유일한_TAGO_식별자를_찾는다() {
        when(client.findCities()).thenReturn(List.of(
                new TagoBusStopClient.City("25", "대전광역시")
        ));
        when(client.findStopsByName("25", "서부소방서")).thenReturn(List.of(
                new TagoBusStopClient.Stop("25", "DJB8002544", "서부소방서", "1234")
        ));

        List<TagoBusStopResolver.TagoStopIdentifier> result = resolver.resolveCandidates(route, leg);

        assertThat(result).containsExactly(new TagoBusStopResolver.TagoStopIdentifier("25", "DJB8002544"));
    }

    @Test
    void 후보가_여러개면_모든_TAGO_식별자를_반환한다() {
        when(client.findCities()).thenReturn(List.of(
                new TagoBusStopClient.City("25", "대전광역시")
        ));
        when(client.findStopsByName("25", "서부소방서")).thenReturn(List.of(
                new TagoBusStopClient.Stop("25", "DJB8002544", "서부소방서", "1234"),
                new TagoBusStopClient.Stop("25", "DJB8002545", "서부소방서", "1235")
        ));

        assertThat(resolver.resolveCandidates(route, leg)).containsExactly(
                new TagoBusStopResolver.TagoStopIdentifier("25", "DJB8002544"),
                new TagoBusStopResolver.TagoStopIdentifier("25", "DJB8002545")
        );
    }

    @Test
    void 지역명과_TAGO_도시명이_행정구역_표현만_달라도_도시코드를_찾는다() {
        when(region.getFullName()).thenReturn("서울특별시 강남구");
        when(leg.getBoardingStopName()).thenReturn("서울세관");
        when(client.findCities()).thenReturn(List.of(
                new TagoBusStopClient.City("11", "서울시")
        ));
        when(client.findStopsByName("11", "서울세관")).thenReturn(List.of(
                new TagoBusStopClient.Stop("11", "SEB123456", "서울세관", "")
        ));

        assertThat(resolver.resolveCandidates(route, leg)).containsExactly(
                new TagoBusStopResolver.TagoStopIdentifier("11", "SEB123456")
        );
    }

    @Test
    void WALK_leg은_정류장_조회하지_않는다() {
        when(leg.getMode()).thenReturn("WALK");

        assertThat(resolver.resolveCandidates(route, leg)).isEmpty();
        verify(client, org.mockito.Mockito.never()).findCities();
    }
}
