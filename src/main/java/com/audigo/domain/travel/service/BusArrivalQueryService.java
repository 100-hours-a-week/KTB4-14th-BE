package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.dto.BusArrivalResponse;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BusArrivalQueryService {

    private static final Logger log = LoggerFactory.getLogger(BusArrivalQueryService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BusArrivalRealtimeService busArrivalRealtimeService;
    private final TagoBusStopLookupService tagoBusStopLookupService;

    public BusArrivalQueryService(
            BusArrivalRealtimeService busArrivalRealtimeService,
            TagoBusStopLookupService tagoBusStopLookupService
    ) {
        this.busArrivalRealtimeService = busArrivalRealtimeService;
        this.tagoBusStopLookupService = tagoBusStopLookupService;
    }

    public BusArrivalLookupResponse findForRoute(
            RouteSegment route,
            String cityCode,
            String nodeId,
            LocalDateTime fetchedAt
    ) {
        LocalDateTime resolvedFetchedAt = fetchedAt == null ? LocalDateTime.now(KST) : fetchedAt;
        if (route == null || route.getId() == null) {
            return BusArrivalLookupResponse.unavailable(route == null ? null : route.getId());
        }

        if (!isBlank(cityCode) && !isBlank(nodeId)) {
            try {
                return busArrivalRealtimeService.findForRoute(
                        route,
                        cityCode,
                        nodeId,
                        resolvedFetchedAt
                );
            } catch (RuntimeException exception) {
                log.warn("TAGO 버스 도착정보 조회에 실패했습니다. routeId={}, cityCode={}, nodeId={}",
                        route.getId(), cityCode, nodeId, exception);
                return BusArrivalLookupResponse.unavailable(route.getId());
            }
        }

        List<BusArrivalResponse> arrivals = new ArrayList<>();
        if (route.getLegs() == null) {
            return BusArrivalLookupResponse.unavailable(route.getId());
        }

        for (RouteSegmentLeg leg : route.getLegs()) {
            if (!isBusLeg(leg)) {
                continue;
            }
            BusArrivalLookupResponse lookup = findForLeg(route, leg, resolvedFetchedAt);
            if (lookup != null && lookup.available()) {
                arrivals.addAll(lookup.arrivals());
            }
        }

        return new BusArrivalLookupResponse(
                route.getId(),
                !arrivals.isEmpty(),
                arrivals,
                arrivals.isEmpty() ? null : resolvedFetchedAt
        );
    }

    private BusArrivalLookupResponse findForLeg(
            RouteSegment route,
            RouteSegmentLeg leg,
            LocalDateTime fetchedAt
    ) {
        List<TagoBusStopLookupService.TagoStopIdentifier> candidates;
        try {
            candidates = tagoBusStopLookupService.findCandidates(route, leg);
        } catch (RuntimeException exception) {
            log.warn("TAGO 정류장 후보 조회에 실패했습니다. routeId={}, legSequence={}",
                    route.getId(), leg.getSequence(), exception);
            return BusArrivalLookupResponse.unavailable(route.getId());
        }

        if (candidates == null || candidates.isEmpty()) {
            return BusArrivalLookupResponse.unavailable(route.getId());
        }

        for (TagoBusStopLookupService.TagoStopIdentifier candidate : candidates) {
            if (candidate == null || isBlank(candidate.cityCode()) || isBlank(candidate.nodeId())) {
                continue;
            }
            try {
                BusArrivalLookupResponse lookup = busArrivalRealtimeService.findForLeg(
                        route,
                        leg,
                        candidate.cityCode(),
                        candidate.nodeId(),
                        fetchedAt
                );
                if (lookup != null && lookup.available()) {
                    return lookup;
                }
            } catch (RuntimeException exception) {
                log.warn("TAGO 버스 도착정보 조회에 실패했습니다. routeId={}, legSequence={}, nodeId={}",
                        route.getId(), leg.getSequence(), candidate.nodeId(), exception);
            }
        }
        return BusArrivalLookupResponse.unavailable(route.getId());
    }

    private static boolean isBusLeg(RouteSegmentLeg leg) {
        if (leg == null || isBlank(leg.getMode())) {
            return false;
        }
        String normalized = leg.getMode().trim().toUpperCase(Locale.ROOT);
        return "BUS".equals(normalized)
                || "EXPRESSBUS".equals(normalized)
                || "INTERCITY_BUS".equals(normalized);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
