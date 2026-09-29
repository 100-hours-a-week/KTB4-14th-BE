package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.dto.PlaceSearchItemResponse;
import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.dto.RequiredPlaceRequest;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.PlaceType;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.TravelPreferenceFood;
import com.audigo.domain.travel.entity.TravelPreferenceTheme;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TravelGenerationRequestFactory {

    private static final int PLACE_SEARCH_PAGE = 1;
    private static final int PLACE_SEARCH_SIZE = 15;

    private final KakaoPlaceSearchService kakaoPlaceSearchService;

    public TravelGenerationRequestFactory(KakaoPlaceSearchService kakaoPlaceSearchService) {
        this.kakaoPlaceSearchService = kakaoPlaceSearchService;
    }

    public AiTravelGenerationRequest from(TravelPlan plan, TravelPlanRequest request) {
        if (request != null && request.preference() != null) {
            return fromRequest(plan, request);
        }
        return fromStoredPlan(plan);
    }

    private AiTravelGenerationRequest fromRequest(TravelPlan plan, TravelPlanRequest request) {
        TravelPreferenceRequest preference = request.preference();
        AiTravelGenerationRequest.Preference aiPreference = new AiTravelGenerationRequest.Preference(
                preference.paceType(),
                preference.transportType(),
                preference.budgetMin(),
                preference.budgetMax(),
                preference.budgetType() == null ? null : preference.budgetType().name(),
                preference.distancePreference(),
                preference.themes().stream().map(Enum::name).toList(),
                preference.foods().stream().map(Enum::name).toList(),
                preference.extraRequest()
        );
        List<AiTravelGenerationRequest.PlaceContext> places = request.requiredPlaces().stream()
                .map(this::fromRequiredPlace)
                .toList();
        return new AiTravelGenerationRequest(
                plan.getId(),
                plan.getRegion().getId(),
                plan.getRegion().getFullName(),
                plan.getArrivalDatetime(),
                plan.getDepartureDatetime(),
                plan.getHeadCount(),
                plan.getCompanionType().name(),
                aiPreference,
                places
        );
    }

    private AiTravelGenerationRequest fromStoredPlan(TravelPlan plan) {
        var preference = plan.getPreference();
        AiTravelGenerationRequest.Preference aiPreference = new AiTravelGenerationRequest.Preference(
                preference.getPaceType(),
                preference.getTransportType(),
                preference.getBudgetMin(),
                preference.getBudgetMax(),
                preference.getBudgetType().name(),
                preference.getDistancePreference(),
                preference.getThemes().stream().map(TravelPreferenceTheme::getTheme).map(Enum::name).toList(),
                preference.getFoods().stream().map(TravelPreferenceFood::getFoodType).map(Enum::name).toList(),
                preference.getExtraRequest()
        );
        List<AiTravelGenerationRequest.PlaceContext> places = plan.getUserRequiredPlaces().stream()
                .map(place -> fromStoredPlace(plan, place))
                .toList();
        return new AiTravelGenerationRequest(
                plan.getId(),
                plan.getRegion().getId(),
                plan.getRegion().getFullName(),
                plan.getArrivalDatetime(),
                plan.getDepartureDatetime(),
                plan.getHeadCount(),
                plan.getCompanionType().name(),
                aiPreference,
                places
        );
    }

    private AiTravelGenerationRequest.PlaceContext fromStoredPlace(
            TravelPlan plan,
            TravelPlanPlace storedPlace
    ) {
        var place = storedPlace.getPlace();
        PlaceSearchItemResponse resolved = resolvePlace(plan, place.getProvider(), place.getProviderPlaceId());
        String address = resolved.roadAddress() == null || resolved.roadAddress().isBlank()
                ? resolved.address()
                : resolved.roadAddress();
        return new AiTravelGenerationRequest.PlaceContext(
                place.getProvider().name(),
                place.getProviderPlaceId(),
                resolved.placeName(),
                address,
                resolved.latitude(),
                resolved.longitude(),
                aiPlaceType(storedPlace.getPlaceType()),
                storedPlace.getPlaceOrder()
        );
    }

    private PlaceSearchItemResponse resolvePlace(
            TravelPlan plan,
            PlaceProvider provider,
            String providerPlaceId
    ) {
        PlaceSearchResponse response = kakaoPlaceSearchService.search(
                plan.getRegion().getId(),
                providerPlaceId,
                PLACE_SEARCH_PAGE,
                PLACE_SEARCH_SIZE
        );
        List<PlaceSearchItemResponse> matches = response == null || response.places() == null
                ? List.of()
                : response.places().stream()
                .filter(candidate -> candidate != null)
                .filter(candidate -> candidate.provider() == provider)
                .filter(candidate -> providerPlaceId.equals(candidate.providerPlaceId()))
                .toList();
        if (matches.size() != 1) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        return matches.get(0);
    }

    private AiTravelGenerationRequest.PlaceContext fromRequiredPlace(RequiredPlaceRequest place) {
        return new AiTravelGenerationRequest.PlaceContext(
                place.provider().name(),
                place.providerPlaceId(),
                place.placeName(),
                place.address(),
                place.latitude(),
                place.longitude(),
                aiPlaceType(place.placeType()),
                place.order()
        );
    }

    private String aiPlaceType(PlaceType placeType) {
        return placeType == null ? PlaceType.TOURISM.name() : placeType.name();
    }
}
