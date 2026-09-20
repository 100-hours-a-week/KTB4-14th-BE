package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.dto.RequiredPlaceRequest;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.TravelPreferenceFood;
import com.audigo.domain.travel.entity.TravelPreferenceTheme;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TravelGenerationRequestFactory {

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
        List<AiTravelGenerationRequest.PlaceContext> places = plan.getRequiredPlaces().stream()
                .map(place -> new AiTravelGenerationRequest.PlaceContext(
                        place.getPlace().getProvider().name(),
                        place.getPlace().getProviderPlaceId(),
                        null,
                        null,
                        null,
                        null,
                        place.getPlaceType(),
                        place.getPlaceOrder()
                ))
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

    private AiTravelGenerationRequest.PlaceContext fromRequiredPlace(RequiredPlaceRequest place) {
        return new AiTravelGenerationRequest.PlaceContext(
                place.provider().name(),
                place.providerPlaceId(),
                place.placeName(),
                place.address(),
                place.latitude(),
                place.longitude(),
                place.placeType(),
                place.order()
        );
    }
}
