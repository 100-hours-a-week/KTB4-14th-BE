package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PublicTransportRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(PublicTransportRealtimeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TravelItineraryMetadataStore metadataStore;
    private final KakaoTransitCoordinateResolver coordinateResolver;
    private final KakaoPublicTransitClient kakaoPublicTransitClient;

    public PublicTransportRealtimeService(
            TravelItineraryMetadataStore metadataStore,
            KakaoTransitCoordinateResolver coordinateResolver,
            KakaoPublicTransitClient kakaoPublicTransitClient
    ) {
        this.metadataStore = metadataStore;
        this.coordinateResolver = coordinateResolver;
        this.kakaoPublicTransitClient = kakaoPublicTransitClient;
    }

    public void refresh(RouteSegment route, LocalDateTime completedAt) {
        if (route == null || route.getTransportType() != TravelTransportType.PUBLIC_TRANSPORT) {
            return;
        }

        TravelItineraryMetadataStore.RouteMetadata current = currentMetadata(route);
        LocalDateTime requestAt = completedAt == null ? LocalDateTime.now(KST) : completedAt;
        try {
            Optional<KakaoTransitCoordinateResolver.Coordinates> coordinates = coordinateResolver.resolve(route);
            if (coordinates.isEmpty()) {
                log.warn("출발·도착 장소 좌표를 조회하지 못해 카카오 경로를 호출하지 않습니다. routeId={}", route.getId());
                metadataStore.putRoute(route.getId(), withoutRealtime(current));
                return;
            }
            KakaoTransitCoordinateResolver.Coordinates resolvedCoordinates = coordinates.get();
            KakaoPublicTransitClient.RouteResult result = kakaoPublicTransitClient.findRoute(
                    resolvedCoordinates.startLongitude(),
                    resolvedCoordinates.startLatitude(),
                    resolvedCoordinates.goalLongitude(),
                    resolvedCoordinates.goalLatitude(),
                    requestAt,
                    KakaoPublicTransitClient.RouteType.ALL
            );

            // Kakao의 total_time은 요청 시각 기준 목적지까지의 예상 소요 시간
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

}
