package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PublicTransportRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(PublicTransportRealtimeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TravelItineraryMetadataStore metadataStore;
    private final KakaoPublicTransitClient kakaoPublicTransitClient;

    public PublicTransportRealtimeService(
            TravelItineraryMetadataStore metadataStore,
            KakaoPublicTransitClient kakaoPublicTransitClient
    ) {
        this.metadataStore = metadataStore;
        this.kakaoPublicTransitClient = kakaoPublicTransitClient;
    }

    public void refresh(RouteSegment route, LocalDateTime completedAt) {
        if (route == null || route.getTransportType() != TravelTransportType.PUBLIC_TRANSPORT) {
            return;
        }

        TravelItineraryMetadataStore.RouteMetadata current = currentMetadata(route);
        PlaceCoordinates coordinates = coordinates(route);
        if (coordinates == null) {
            log.warn("출발·도착 장소 좌표가 없어 카카오 대중교통 경로를 조회하지 않습니다. routeId={}", route.getId());
            metadataStore.putRoute(route.getId(), withoutRealtime(current));
            return;
        }

        LocalDateTime requestAt = completedAt == null ? LocalDateTime.now(KST) : completedAt;
        try {
            KakaoPublicTransitClient.RouteResult result = kakaoPublicTransitClient.findRoute(
                    coordinates.startLongitude(),
                    coordinates.startLatitude(),
                    coordinates.goalLongitude(),
                    coordinates.goalLatitude(),
                    requestAt,
                    KakaoPublicTransitClient.RouteType.ALL
            );

            // Kakao의 total_time은 요청 시각 기준 목적지까지의 예상 소요 시간이다.
            // 기존 FE 응답 필드와의 호환을 위해 분 단위로 next_arrival_minutes에 함께 전달한다.
            metadataStore.putRoute(route.getId(), new TravelItineraryMetadataStore.RouteMetadata(
                    current.lineName(),
                    current.vehicleNumber(),
                    toMinutes(result.totalTimeSeconds()),
                    result.requestedDepartureAt(),
                    result.estimatedArrivalAt(),
                    true,
                    LocalDateTime.now(KST)
            ));
        } catch (RuntimeException exception) {
            log.warn("카카오 대중교통 경로 갱신에 실패해 AI 계산값을 유지합니다. routeId={}, 요청 시각={}",
                    route.getId(), requestAt, exception);
            metadataStore.putRoute(route.getId(), withoutRealtime(current));
        }
    }

    private TravelItineraryMetadataStore.RouteMetadata currentMetadata(RouteSegment route) {
        if (route.getId() == null) {
            return new TravelItineraryMetadataStore.RouteMetadata(
                    null, null, null, null, null, false, null);
        }
        TravelItineraryMetadataStore.RouteMetadata metadata = metadataStore.route(route.getId());
        if (metadata != null) {
            return metadata;
        }
        return new TravelItineraryMetadataStore.RouteMetadata(
                null, null, null, null, null, false, null);
    }

    private PlaceCoordinates coordinates(RouteSegment route) {
        if (route.getFromItineraryItem() == null || route.getToItineraryItem() == null) {
            return null;
        }

        Long startItemId = route.getFromItineraryItem().getId();
        Long goalItemId = route.getToItineraryItem().getId();
        if (startItemId == null || goalItemId == null) {
            return null;
        }
        TravelItineraryMetadataStore.PlaceMetadata start = metadataStore.place(startItemId);
        TravelItineraryMetadataStore.PlaceMetadata goal = metadataStore.place(goalItemId);
        if (!hasCoordinates(start) || !hasCoordinates(goal)) {
            return null;
        }
        return new PlaceCoordinates(
                start.longitude(),
                start.latitude(),
                goal.longitude(),
                goal.latitude()
        );
    }

    private static boolean hasCoordinates(TravelItineraryMetadataStore.PlaceMetadata metadata) {
        return metadata != null && metadata.latitude() != null && metadata.longitude() != null;
    }

    private static int toMinutes(int seconds) {
        return (seconds + 59) / 60;
    }

    private static TravelItineraryMetadataStore.RouteMetadata withoutRealtime(
            TravelItineraryMetadataStore.RouteMetadata metadata
    ) {
        return new TravelItineraryMetadataStore.RouteMetadata(
                metadata.lineName(),
                metadata.vehicleNumber(),
                metadata.nextArrivalMinutes(),
                metadata.estimatedDepartureAt(),
                metadata.estimatedArrivalAt(),
                false,
                metadata.lastRefreshedAt()
        );
    }

    private record PlaceCoordinates(
            BigDecimal startLongitude,
            BigDecimal startLatitude,
            BigDecimal goalLongitude,
            BigDecimal goalLatitude
    ) {
    }
}
