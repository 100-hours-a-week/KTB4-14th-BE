package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.PlaceSearchItemResponse;
import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KakaoTransitCoordinateResolverTest {

    @Test
    void 카카오_장소_ID를_기준으로_출발지와_도착지_좌표를_매번_조회한다() {
        KakaoPlaceSearchService placeSearchService = mock(KakaoPlaceSearchService.class);
        KakaoTransitCoordinateResolver resolver = new KakaoTransitCoordinateResolver(placeSearchService);
        RouteContext context = routeContext();
        when(placeSearchService.search(7L, "start-place", 1, 15))
                .thenReturn(response(candidate("start-place", "35.100", "129.100")));
        when(placeSearchService.search(7L, "goal-place", 1, 15))
                .thenReturn(response(candidate("goal-place", "35.200", "129.200")));

        Optional<KakaoTransitCoordinateResolver.Coordinates> result = resolver.resolve(context.route());

        assertThat(result).isPresent();
        assertThat(result.get().startLongitude()).isEqualByComparingTo("129.100");
        assertThat(result.get().startLatitude()).isEqualByComparingTo("35.100");
        assertThat(result.get().goalLongitude()).isEqualByComparingTo("129.200");
        assertThat(result.get().goalLatitude()).isEqualByComparingTo("35.200");
        verify(placeSearchService).search(7L, "start-place", 1, 15);
        verify(placeSearchService).search(7L, "goal-place", 1, 15);
    }

    @Test
    void 장소_ID가_없거나_여러_후보와_일치하면_좌표를_반환하지_않는다() {
        KakaoPlaceSearchService placeSearchService = mock(KakaoPlaceSearchService.class);
        KakaoTransitCoordinateResolver resolver = new KakaoTransitCoordinateResolver(placeSearchService);
        RouteContext context = routeContext();
        when(placeSearchService.search(7L, "start-place", 1, 15))
                .thenReturn(response(
                        candidate("start-place", "35.100", "129.100"),
                        candidate("start-place", "35.101", "129.101")
                ));

        assertThat(resolver.resolve(context.route())).isEmpty();
        verify(placeSearchService).search(7L, "start-place", 1, 15);
        verify(placeSearchService, never()).search(eq(7L), eq("goal-place"), eq(1), eq(15));
    }

    private RouteContext routeContext() {
        RouteSegment route = mock(RouteSegment.class);
        TravelPlan plan = mock(TravelPlan.class);
        Region region = mock(Region.class);
        ItineraryItem startItem = mock(ItineraryItem.class);
        ItineraryItem goalItem = mock(ItineraryItem.class);
        TravelPlanPlace startPlanPlace = mock(TravelPlanPlace.class);
        TravelPlanPlace goalPlanPlace = mock(TravelPlanPlace.class);
        Place startPlace = mock(Place.class);
        Place goalPlace = mock(Place.class);

        when(route.getTravelPlan()).thenReturn(plan);
        when(route.getFromItineraryItem()).thenReturn(startItem);
        when(route.getToItineraryItem()).thenReturn(goalItem);
        when(plan.getRegion()).thenReturn(region);
        when(region.getId()).thenReturn(7L);
        when(startItem.getTravelPlanPlace()).thenReturn(startPlanPlace);
        when(goalItem.getTravelPlanPlace()).thenReturn(goalPlanPlace);
        when(startPlanPlace.getPlace()).thenReturn(startPlace);
        when(goalPlanPlace.getPlace()).thenReturn(goalPlace);
        when(startPlace.getProvider()).thenReturn(PlaceProvider.KAKAO);
        when(goalPlace.getProvider()).thenReturn(PlaceProvider.KAKAO);
        when(startPlace.getProviderPlaceId()).thenReturn("start-place");
        when(goalPlace.getProviderPlaceId()).thenReturn("goal-place");
        return new RouteContext(route);
    }

    private PlaceSearchResponse response(PlaceSearchItemResponse... candidates) {
        return new PlaceSearchResponse(List.of(candidates), 1, 15, true, candidates.length);
    }

    private PlaceSearchItemResponse candidate(String providerPlaceId, String latitude, String longitude) {
        return new PlaceSearchItemResponse(
                PlaceProvider.KAKAO,
                providerPlaceId,
                "장소",
                "주소",
                "도로명 주소",
                new BigDecimal(latitude),
                new BigDecimal(longitude)
        );
    }

    private record RouteContext(RouteSegment route) {
    }
}
