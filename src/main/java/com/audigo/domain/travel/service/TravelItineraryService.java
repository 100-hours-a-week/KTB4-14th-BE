package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.ItineraryCompletionResponse;
import com.audigo.domain.travel.dto.ItineraryResponse;
import com.audigo.domain.travel.dto.RouteRecalculationResponse;
import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.dto.BusArrivalResponse;
import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TravelItineraryService {

    private static final Logger log = LoggerFactory.getLogger(TravelItineraryService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TravelPlanRepository travelPlanRepository;
    private final ItineraryDayRepository dayRepository;
    private final ItineraryItemRepository itemRepository;
    private final RouteSegmentRepository routeRepository;
    private final TravelItineraryMetadataStore metadataStore;
    private final BusArrivalRealtimeService busArrivalRealtimeService;
    private final TagoBusStopResolver tagoBusStopResolver;
    private final PublicTransportRealtimeService realtimeService;

    public TravelItineraryService(
            TravelPlanRepository travelPlanRepository,
            ItineraryDayRepository dayRepository,
            ItineraryItemRepository itemRepository,
            RouteSegmentRepository routeRepository,
            TravelItineraryMetadataStore metadataStore,
            BusArrivalRealtimeService busArrivalRealtimeService,
            TagoBusStopResolver tagoBusStopResolver,
            PublicTransportRealtimeService realtimeService
    ) {
        this.travelPlanRepository = travelPlanRepository;
        this.dayRepository = dayRepository;
        this.itemRepository = itemRepository;
        this.routeRepository = routeRepository;
        this.metadataStore = metadataStore;
        this.busArrivalRealtimeService = busArrivalRealtimeService;
        this.tagoBusStopResolver = tagoBusStopResolver;
        this.realtimeService = realtimeService;
    }

    public ItineraryResponse getItinerary(Long userId, Long travelPlanId) {
        TravelPlan plan = ownedPlan(userId, travelPlanId);
        if (plan.getStatus() != TravelPlanStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<ItineraryDay> days = dayRepository.findAllByTravelPlanIdOrderByDayNumberAsc(travelPlanId);
        List<RouteSegment> routes = routeRepository.findAllByTravelPlanId(travelPlanId).stream()
                .sorted(java.util.Comparator.comparingInt(RouteSegment::getOrder))
                .toList();
        Map<Long, Map<Integer, BusArrivalResponse>> busArrivalsByRoute = loadBusArrivals(routes);
        Map<Long, List<RouteSegment>> routesByDay = routes.stream()
                .collect(Collectors.groupingBy(route -> route.getFromItineraryItem().getItineraryDay().getId()));

        List<ItineraryResponse.ItineraryDayResponse> responseDays = days.stream()
                .map(day -> toDayResponse(
                        day,
                        routesByDay.getOrDefault(day.getId(), List.of()),
                        busArrivalsByRoute
                ))
                .toList();
        return ItineraryResponse.from(plan, responseDays);
    }

    @Transactional
    public ItineraryCompletionResponse updateCompletion(Long userId, Long itemId, boolean completed) {
        ItineraryItem item = itemRepository.findByIdAndItineraryDayTravelPlanUserId(itemId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        TravelPlan plan = item.getItineraryDay().getTravelPlan();
        if (plan.getStatus() != TravelPlanStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // 같은 상태로 다시 요청해도 완료 시각을 바꾸거나 실시간 API를 중복 호출하지 않는다
        if (item.isCompleted() == completed) {
            return new ItineraryCompletionResponse(item.getId(), item.isCompleted(), item.getCompletedAt());
        }
        LocalDateTime changedAt = completed ? LocalDateTime.now() : null;
        item.updateCompletion(completed, changedAt);
        if (completed) {
            refreshPublicRoutes(plan.getId(), item, changedAt);
        }
        return new ItineraryCompletionResponse(item.getId(), item.isCompleted(), item.getCompletedAt());
    }

    @Transactional
    public RouteRecalculationResponse recalculateRoutes(Long userId, Long travelPlanId) {
        TravelPlan plan = ownedPlan(userId, travelPlanId);
        if (plan.getStatus() != TravelPlanStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<RouteSegment> routes = routeRepository.findAllByTravelPlanId(travelPlanId).stream()
                .sorted(java.util.Comparator.comparingInt(RouteSegment::getOrder))
                .toList();
        LocalDateTime refreshedAt = LocalDateTime.now(KST);
        routes.stream()
                .filter(route -> route.getTransportType() == TravelTransportType.PUBLIC_TRANSPORT)
                .forEach(route -> {
                    try {
                        realtimeService.refresh(route, refreshedAt);
                    } catch (RuntimeException exception) {
                        // 카카오 장애가 경로 재계산 응답 자체를 실패시키지 않도록 기존 메타데이터를 유지한다.
                        log.warn("대중교통 경로 실시간 갱신에 실패했지만 경로 응답은 반환합니다. routeId={}",
                                route.getId(), exception);
                    }
                });
        return new RouteRecalculationResponse(
                travelPlanId,
                routes.stream().map(route -> ItineraryResponse.RouteSegmentResponse.from(
                        route, metadataStore.route(route.getId()))).toList()
        );
    }

    private ItineraryResponse.ItineraryDayResponse toDayResponse(
            ItineraryDay day,
            List<RouteSegment> routes,
            Map<Long, Map<Integer, BusArrivalResponse>> busArrivalsByRoute
    ) {
        List<ItineraryItem> items = itemRepository.findAllByItineraryDayIdOrderBySequenceAsc(day.getId());
        List<ItineraryResponse.ItineraryItemResponse> itemResponses = items.stream()
                .map(item -> ItineraryResponse.ItineraryItemResponse.from(item, metadataStore))
                .toList();
        List<ItineraryResponse.RouteSegmentResponse> routeResponses = routes.stream()
                .map(route -> ItineraryResponse.RouteSegmentResponse.from(
                        route,
                        metadataStore.route(route.getId()),
                        busArrivalsByRoute.getOrDefault(route.getId(), Map.of())
                ))
                .toList();
        return new ItineraryResponse.ItineraryDayResponse(
                day.getId(), day.getDayNumber(), day.getTravelDate(), itemResponses, routeResponses);
    }

    private Map<Long, Map<Integer, BusArrivalResponse>> loadBusArrivals(List<RouteSegment> routes) {
        Map<Long, Map<Integer, BusArrivalResponse>> arrivalsByRoute = new HashMap<>();
        LocalDateTime fetchedAt = LocalDateTime.now(KST);
        for (RouteSegment route : routes) {
            if (route == null || route.getLegs() == null) {
                continue;
            }
            for (RouteSegmentLeg leg : route.getLegs()) {
                if (!isBusLeg(leg)) {
                    continue;
                }
                try {
                    BusArrivalLookupResponse lookup = lookupBusArrivals(route, leg, fetchedAt);
                    if (lookup == null || !lookup.available()) {
                        continue;
                    }
                    for (BusArrivalResponse response : lookup.arrivals()) {
                        arrivalsByRoute
                                .computeIfAbsent(route.getId(), ignored -> new HashMap<>())
                                .putIfAbsent(response.legSequence(), response);
                    }
                } catch (RuntimeException exception) {
                    // TAGO 실시간 정보는 부가정보이므로 일정 조회 자체를 실패시키지 않는다.
                    log.warn("버스 실시간 정보 결합에 실패했지만 일정은 반환합니다. routeId={}, legSequence={}",
                            route.getId(), leg.getSequence(), exception);
                }
            }
        }
        return arrivalsByRoute;
    }

    private BusArrivalLookupResponse lookupBusArrivals(
            RouteSegment route,
            RouteSegmentLeg leg,
            LocalDateTime fetchedAt
    ) {
        List<TagoBusStopResolver.TagoStopIdentifier> candidates =
                tagoBusStopResolver.resolveCandidates(route, leg);
        for (TagoBusStopResolver.TagoStopIdentifier identifier : candidates) {
            BusArrivalLookupResponse lookup = busArrivalRealtimeService.findForLeg(
                        route,
                        leg,
                        identifier.cityCode(),
                        identifier.nodeId(),
                        fetchedAt
                );
            if (lookup != null && lookup.available()) {
                return lookup;
            }
        }
        return BusArrivalLookupResponse.unavailable(route.getId());
    }

    private static boolean isBusLeg(RouteSegmentLeg leg) {
        if (leg == null || leg.getMode() == null) {
            return false;
        }
        String mode = leg.getMode().trim().toUpperCase(java.util.Locale.ROOT);
        return "BUS".equals(mode)
                || "EXPRESSBUS".equals(mode)
                || "INTERCITY_BUS".equals(mode);
    }

    private void refreshPublicRoutes(Long planId, ItineraryItem completedItem, LocalDateTime completedAt) {
        routeRepository.findAllByTravelPlanId(planId).stream()
                .filter(route -> route.getTransportType() == TravelTransportType.PUBLIC_TRANSPORT)
                .filter(route -> java.util.Objects.equals(route.getFromItineraryItem().getId(), completedItem.getId())
                        || java.util.Objects.equals(route.getToItineraryItem().getId(), completedItem.getId()))
                .forEach(route -> {
                    try {
                        realtimeService.refresh(route, completedAt);
                    } catch (RuntimeException exception) {
                        // 실시간 API 장애가 일정 완료 자체를 실패시키지 않도록 한 번 더 보호
                        log.warn("인접 대중교통 경로 갱신에 실패했지만 일정 완료는 유지합니다. routeId={}",
                                route.getId(), exception);
                    }
                });
    }

    private TravelPlan ownedPlan(Long userId, Long travelPlanId) {
        return travelPlanRepository.findByIdAndUserId(travelPlanId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }
}
