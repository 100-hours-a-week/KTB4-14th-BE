package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.service.TravelItineraryMetadataStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record ItineraryResponse(
        @JsonProperty("travel_plan_id") Long travelPlanId,
        String title,
        String destination,
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        String status,
        int nights,
        @JsonProperty("day_count") int dayCount,
        @JsonProperty("days") List<ItineraryDayResponse> days
) {
    public ItineraryResponse {
        days = days == null ? List.of() : List.copyOf(days);
    }

    public static ItineraryResponse from(
            TravelPlan travelPlan,
            List<ItineraryDayResponse> days
    ) {
        LocalDate startDate = travelPlan.getArrivalDatetime().toLocalDate();
        LocalDate endDate = travelPlan.getDepartureDatetime().toLocalDate();
        int nights = Math.max(0, (int) (endDate.toEpochDay() - startDate.toEpochDay()));
        return new ItineraryResponse(
                travelPlan.getId(),
                travelPlan.getRegion().getFullName() + " 여행",
                travelPlan.getRegion().getFullName(),
                startDate,
                endDate,
                travelPlan.getStatus().name(),
                nights,
                days.size(),
                days
        );
    }

    public record ItineraryDayResponse(
            @JsonProperty("itinerary_day_id") Long itineraryDayId,
            @JsonProperty("day_number") int dayNumber,
            @JsonProperty("date") LocalDate date,
            List<ItineraryItemResponse> items,
            List<RouteSegmentResponse> routes
    ) {
        public ItineraryDayResponse {
            items = items == null ? List.of() : List.copyOf(items);
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    public record ItineraryItemResponse(
            @JsonProperty("itinerary_item_id") Long itineraryItemId,
            @JsonProperty("travel_plan_place_id") Long travelPlanPlaceId,
            @JsonProperty("provider") String provider,
            @JsonProperty("provider_place_id") String providerPlaceId,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("place_type") String placeType,
            String address,
            @JsonProperty("latitude") java.math.BigDecimal latitude,
            @JsonProperty("longitude") java.math.BigDecimal longitude,
            @JsonProperty("start_time") LocalTime startTime,
            @JsonProperty("end_time") LocalTime endTime,
            @JsonProperty("item_type") String itemType,
            @JsonProperty("is_completed") boolean completed,
            @JsonProperty("completed_at") LocalDateTime completedAt
    ) {
        public static ItineraryItemResponse from(ItineraryItem item, TravelItineraryMetadataStore metadataStore) {
            TravelPlanPlace planPlace = item.getTravelPlanPlace();
            TravelItineraryMetadataStore.PlaceMetadata metadata = metadataStore.place(item.getId());
            return new ItineraryItemResponse(
                    item.getId(),
                    planPlace.getId(),
                    planPlace.getPlace().getProvider().name(),
                    planPlace.getPlace().getProviderPlaceId(),
                    metadata == null ? null : metadata.placeName(),
                    planPlace.getPlaceType().name(),
                    metadata == null ? null : metadata.address(),
                    metadata == null ? null : metadata.latitude(),
                    metadata == null ? null : metadata.longitude(),
                    item.getStartTime(),
                    item.getEndTime(),
                    item.getItemType().name(),
                    item.isCompleted(),
                    item.getCompletedAt()
            );
        }
    }

    public record RouteSegmentResponse(
            @JsonProperty("route_segment_id") Long routeSegmentId,
            @JsonProperty("from_itinerary_item_id") Long fromItineraryItemId,
            @JsonProperty("to_itinerary_item_id") Long toItineraryItemId,
            @JsonProperty("transport_type") TravelTransportType transportType,
            @JsonProperty("duration_minutes") Integer durationMinutes,
            @JsonProperty("distance_meter") Integer distanceMeter,
            @JsonProperty("cost") Integer cost,
            @JsonProperty("order") int order,
            @JsonProperty("line_name") String lineName,
            @JsonProperty("vehicle_number") String vehicleNumber,
            @JsonProperty("next_arrival_minutes") Integer nextArrivalMinutes,
            @JsonProperty("estimated_departure_at") LocalDateTime estimatedDepartureAt,
            @JsonProperty("estimated_arrival_at") LocalDateTime estimatedArrivalAt,
            @JsonProperty("realtime") boolean realtime,
            @JsonProperty("last_refreshed_at") LocalDateTime lastRefreshedAt
    ) {
        public static RouteSegmentResponse from(
                RouteSegment route,
                TravelItineraryMetadataStore.RouteMetadata metadata
        ) {
            return new RouteSegmentResponse(
                    route.getId(),
                    route.getFromItineraryItem().getId(),
                    route.getToItineraryItem().getId(),
                    route.getTransportType(),
                    route.getDurationMinutes(),
                    route.getDistanceMeter(),
                    route.getCost(),
                    route.getOrder(),
                    metadata == null ? null : metadata.lineName(),
                    metadata == null ? null : metadata.vehicleNumber(),
                    metadata == null ? null : metadata.nextArrivalMinutes(),
                    metadata == null ? null : metadata.estimatedDepartureAt(),
                    metadata == null ? null : metadata.estimatedArrivalAt(),
                    metadata != null && metadata.realtime(),
                    metadata == null ? null : metadata.lastRefreshedAt()
            );
        }
    }
}
