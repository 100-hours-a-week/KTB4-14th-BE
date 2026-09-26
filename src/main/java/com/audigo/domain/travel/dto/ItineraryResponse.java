package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
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
            @JsonProperty("total_fare_amount") Integer totalFareAmount,
            @JsonProperty("order") int order,
            /** AI의 legs 전체를 백엔드 표준 구조로 변환한 상세 이동 구간 */
            @JsonProperty("legs") List<RouteLegResponse> legs,
            /** 기존 실시간 도착정보 호환을 위해 유지하는 대표 노선·차량 값 */
            @JsonProperty("line_name") String lineName,
            @JsonProperty("vehicle_number") String vehicleNumber,
            @JsonProperty("next_arrival_minutes") Integer nextArrivalMinutes,
            @JsonProperty("estimated_departure_at") LocalDateTime estimatedDepartureAt,
            @JsonProperty("estimated_arrival_at") LocalDateTime estimatedArrivalAt,
            @JsonProperty("realtime") boolean realtime,
            @JsonProperty("last_refreshed_at") LocalDateTime lastRefreshedAt
    ) {
        public RouteSegmentResponse {
            legs = legs == null ? List.of() : List.copyOf(legs);
        }

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
                    route.getTotalFareAmount(),
                    route.getOrder(),
                    route.getLegs().stream().map(RouteLegResponse::from).toList(),
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

    public record RouteLegResponse(
            @JsonProperty("sequence") int sequence,
            @JsonProperty("mode") String mode,
            @JsonProperty("boarding_stop") StopResponse boardingStop,
            @JsonProperty("alighting_stop") StopResponse alightingStop,
            @JsonProperty("duration_minute") Integer durationMinute,
            @JsonProperty("distance_meter") Integer distanceMeter,
            @JsonProperty("bus_number") List<String> busNumbers,
            @JsonProperty("subway_line") List<String> subwayLines
    ) {
        public RouteLegResponse {
            busNumbers = busNumbers == null ? List.of() : List.copyOf(busNumbers);
            subwayLines = subwayLines == null ? List.of() : List.copyOf(subwayLines);
        }

        private static RouteLegResponse from(RouteSegmentLeg leg) {
            return new RouteLegResponse(
                    leg.getSequence(),
                    leg.getMode(),
                    new StopResponse(leg.getBoardingStopName(), leg.getBoardingStationNumber()),
                    new StopResponse(leg.getAlightingStopName(), leg.getAlightingStationNumber()),
                    leg.getDurationMinute(),
                    leg.getDistanceMeter(),
                    leg.getBusNumbers(),
                    leg.getSubwayLines()
            );
        }
    }

    public record StopResponse(
            @JsonProperty("name") String name,
            @JsonProperty("station_number") String stationNumber
    ) {
    }
}
