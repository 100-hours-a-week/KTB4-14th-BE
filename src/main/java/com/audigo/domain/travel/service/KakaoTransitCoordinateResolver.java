package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.PlaceSearchItemResponse;
import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * DB에 좌표를 저장하지 않고 카카오 장소 ID로 대중교통 조회용 좌표를 해석한다.
 */
@Component
public class KakaoTransitCoordinateResolver {

    private static final int SEARCH_PAGE = 1;
    private static final int SEARCH_SIZE = 15;

    private final KakaoPlaceSearchService kakaoPlaceSearchService;

    public KakaoTransitCoordinateResolver(KakaoPlaceSearchService kakaoPlaceSearchService) {
        this.kakaoPlaceSearchService = kakaoPlaceSearchService;
    }

    public Optional<Coordinates> resolve(RouteSegment route) {
        if (route == null
                || route.getTravelPlan() == null
                || route.getTravelPlan().getRegion() == null
                || route.getFromItineraryItem() == null
                || route.getToItineraryItem() == null) {
            return Optional.empty();
        }

        Long regionId = route.getTravelPlan().getRegion().getId();
        if (regionId == null) {
            return Optional.empty();
        }

        Optional<PlaceCoordinate> start = resolveItem(regionId, route.getFromItineraryItem());
        if (start.isEmpty()) {
            return Optional.empty();
        }
        Optional<PlaceCoordinate> goal = resolveItem(regionId, route.getToItineraryItem());
        if (goal.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Coordinates(
                start.get().longitude(),
                start.get().latitude(),
                goal.get().longitude(),
                goal.get().latitude()
        ));
    }

    private Optional<PlaceCoordinate> resolveItem(Long regionId, ItineraryItem item) {
        TravelPlanPlace travelPlanPlace = item.getTravelPlanPlace();
        if (travelPlanPlace == null) {
            return Optional.empty();
        }
        Place place = travelPlanPlace.getPlace();
        if (place == null
                || place.getProvider() != PlaceProvider.KAKAO
                || place.getProviderPlaceId() == null
                || place.getProviderPlaceId().isBlank()) {
            return Optional.empty();
        }

        PlaceSearchResponse response = kakaoPlaceSearchService.search(
                regionId,
                place.getProviderPlaceId(),
                SEARCH_PAGE,
                SEARCH_SIZE
        );
        List<PlaceSearchItemResponse> matches = response == null || response.places() == null
                ? List.of()
                : response.places().stream()
                .filter(candidate -> place.getProviderPlaceId().equals(candidate.providerPlaceId()))
                .filter(candidate -> candidate.latitude() != null && candidate.longitude() != null)
                .toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }

        PlaceSearchItemResponse match = matches.get(0);
        return Optional.of(new PlaceCoordinate(match.longitude(), match.latitude()));
    }

    public record Coordinates(
            BigDecimal startLongitude,
            BigDecimal startLatitude,
            BigDecimal goalLongitude,
            BigDecimal goalLatitude
    ) {
    }

    private record PlaceCoordinate(
            BigDecimal longitude,
            BigDecimal latitude
    ) {
    }
}
