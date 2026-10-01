package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.dto.BusArrivalResponse;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 버스 도착정보 조회 서비스.
 * TAGO 도시 코드와 정류소 ID로 도착정보를 조회한다. 노선 필터는 저장된 route의 버스 번호를 사용한다.
 */
@Service
@Transactional(readOnly = true)
public class BusArrivalRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(BusArrivalRealtimeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TAGO_SOURCE = "TAGO";

    private final TagoBusArrivalClient tagoClient;

    public BusArrivalRealtimeService(TagoBusArrivalClient tagoClient) {
        this.tagoClient = tagoClient;
    }

    public BusArrivalLookupResponse findForRoute(
            RouteSegment route,
            String cityCode,
            String nodeId,
            LocalDateTime fetchedAt
    ) {
        LocalDateTime resolvedFetchedAt = fetchedAt == null ? LocalDateTime.now(KST) : fetchedAt;
        if (route == null || route.getId() == null
                || route.getTransportType() != TravelTransportType.PUBLIC_TRANSPORT) {
            return BusArrivalLookupResponse.unavailable(route == null ? null : route.getId());
        }

        Map<String, RouteBus> routeBuses = routeBuses(route);
        return findForRouteBuses(route, routeBuses, cityCode, nodeId, resolvedFetchedAt);
    }

    public BusArrivalLookupResponse findForLeg(
            RouteSegment route,
            RouteSegmentLeg leg,
            String cityCode,
            String nodeId,
            LocalDateTime fetchedAt
    ) {
        LocalDateTime resolvedFetchedAt = fetchedAt == null ? LocalDateTime.now(KST) : fetchedAt;
        if (route == null || route.getId() == null
                || route.getTransportType() != TravelTransportType.PUBLIC_TRANSPORT
                || leg == null || !isBusMode(leg.getMode())) {
            return BusArrivalLookupResponse.unavailable(route == null ? null : route.getId());
        }
        return findForRouteBuses(
                route,
                routeBuses(leg),
                cityCode,
                nodeId,
                resolvedFetchedAt
        );
    }

    private BusArrivalLookupResponse findForRouteBuses(
            RouteSegment route,
            Map<String, RouteBus> routeBuses,
            String cityCode,
            String nodeId,
            LocalDateTime fetchedAt
    ) {
        if (isBlank(cityCode) || isBlank(nodeId) || routeBuses.isEmpty()) {
            return BusArrivalLookupResponse.unavailable(route.getId());
        }

        List<TagoBusArrivalClient.Arrival> sourceArrivals;
        try {
            sourceArrivals = tagoClient.findArrivals(cityCode.trim(), nodeId.trim());
        } catch (RuntimeException exception) {
            log.warn("버스 도착정보 조회에 실패했습니다. routeId={}, cityCode={}, nodeId={}",
                    route.getId(), cityCode, nodeId, exception);
            return BusArrivalLookupResponse.unavailable(route.getId());
        }

        Map<String, TagoBusArrivalClient.Arrival> nextArrivalByRoute = new LinkedHashMap<>();
        for (TagoBusArrivalClient.Arrival arrival : sourceArrivals) {
            String normalizedRouteNumber = normalizeRoute(arrival.routeNumber());
            if (normalizedRouteNumber == null
                    || arrival.arrivalSeconds() == null
                    || !routeBuses.containsKey(normalizedRouteNumber)) {
                continue;
            }
            nextArrivalByRoute.merge(
                    normalizedRouteNumber,
                    arrival,
                    (current, candidate) -> candidate.arrivalSeconds() < current.arrivalSeconds()
                            ? candidate : current
            );
        }

        List<BusArrivalResponse> arrivals = nextArrivalByRoute.entrySet().stream()
                .map(entry -> toResponse(
                        routeBuses.get(entry.getKey()),
                        entry.getValue(),
                        fetchedAt
                ))
                .sorted(Comparator.comparingInt(BusArrivalResponse::legSequence)
                        .thenComparing(BusArrivalResponse::nextArrivalMinutes))
                .toList();

        return new BusArrivalLookupResponse(route.getId(), !arrivals.isEmpty(), arrivals, fetchedAt);
    }

    public BusArrivalLookupResponse findForRoute(RouteSegment route, LocalDateTime fetchedAt) {
        return findForRoute(route, null, null, fetchedAt);
    }

    private static Map<String, RouteBus> routeBuses(RouteSegment route) {
        Map<String, RouteBus> routeBuses = new LinkedHashMap<>();
        List<RouteSegmentLeg> legs = route.getLegs() == null ? List.of() : route.getLegs();
        for (RouteSegmentLeg leg : legs) {
            addRouteBuses(routeBuses, leg);
        }
        return routeBuses;
    }

    private static Map<String, RouteBus> routeBuses(RouteSegmentLeg leg) {
        Map<String, RouteBus> routeBuses = new LinkedHashMap<>();
        addRouteBuses(routeBuses, leg);
        return routeBuses;
    }

    private static void addRouteBuses(Map<String, RouteBus> routeBuses, RouteSegmentLeg leg) {
        if (leg == null || !isBusMode(leg.getMode()) || leg.getBusNumbers() == null) {
            return;
        }
        for (String busNumber : leg.getBusNumbers()) {
            String normalizedBusNumber = normalizeRoute(busNumber);
            if (normalizedBusNumber != null) {
                routeBuses.putIfAbsent(
                        normalizedBusNumber,
                        new RouteBus(leg.getSequence(), busNumber.trim())
                );
            }
        }
    }

    private static BusArrivalResponse toResponse(
            RouteBus routeBus,
            TagoBusArrivalClient.Arrival arrival,
            LocalDateTime fetchedAt
    ) {
        String routeNumber = isBlank(arrival.routeNumber())
                ? routeBus.routeNumber()
                : arrival.routeNumber();
        return new BusArrivalResponse(
                routeBus.legSequence(),
                routeNumber,
                toMinutes(arrival.arrivalSeconds()),
                fetchedAt.plusSeconds(arrival.arrivalSeconds()),
                arrival.remainingStops(),
                TAGO_SOURCE,
                fetchedAt
        );
    }

    private static boolean isBusMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return "BUS".equals(normalized)
                || "EXPRESSBUS".equals(normalized)
                || "INTERCITY_BUS".equals(normalized);
    }

    private static String normalizeRoute(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", "")
                .replaceFirst("번$", "")
                .toUpperCase(Locale.ROOT);
    }

    private static int toMinutes(int seconds) {
        return (seconds + 59) / 60;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RouteBus(int legSequence, String routeNumber) {
    }
}
