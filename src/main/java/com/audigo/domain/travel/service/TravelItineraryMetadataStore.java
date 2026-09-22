package com.audigo.domain.travel.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 장소 이름·주소·좌표와 대중교통 부가 정보처럼 DB에 저장하지 않는 결과 메타데이터를 보관
// 서버 재시작 뒤에는 비워지므로, 그 경우 응답에는 영속화된 provider 식별자만 남음
@Component
public class TravelItineraryMetadataStore {

    private final Map<Long, PlaceMetadata> placesByItemId = new ConcurrentHashMap<>();
    private final Map<Long, RouteMetadata> routesById = new ConcurrentHashMap<>();

    public void putPlace(Long itineraryItemId, PlaceMetadata metadata) {
        if (itineraryItemId != null && metadata != null) {
            placesByItemId.put(itineraryItemId, metadata);
        }
    }

    public PlaceMetadata place(Long itineraryItemId) {
        return placesByItemId.get(itineraryItemId);
    }

    public void putRoute(Long routeId, RouteMetadata metadata) {
        if (routeId != null && metadata != null) {
            routesById.put(routeId, metadata);
        }
    }

    public RouteMetadata route(Long routeId) {
        return routesById.get(routeId);
    }

    public void removeForPlan(Iterable<Long> itemIds, Iterable<Long> routeIds) {
        if (itemIds != null) {
            itemIds.forEach(placesByItemId::remove);
        }
        if (routeIds != null) {
            routeIds.forEach(routesById::remove);
        }
    }

    public record PlaceMetadata(
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }

    public record RouteMetadata(
            String lineName,
            String vehicleNumber,
            Integer nextArrivalMinutes,
            LocalDateTime estimatedDepartureAt,
            LocalDateTime estimatedArrivalAt,
            boolean realtime,
            LocalDateTime lastRefreshedAt
    ) {
    }
}
