package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.ItineraryCompletionResponse;
import com.audigo.domain.travel.dto.ItineraryResponse;
import com.audigo.domain.travel.dto.RouteRecalculationResponse;
import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.dto.PlaceSearchItemResponse;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TravelItineraryService {

    private final TravelPlanRepository travelPlanRepository;
    private final ItineraryDayRepository dayRepository;
    private final ItineraryItemRepository itemRepository;
    private final RouteSegmentRepository routeRepository;
    private final TravelItineraryMetadataStore metadataStore;
    private final PublicTransportRealtimeService realtimeService;
    private final KakaoPlaceSearchService kakaoPlaceSearchService;

    public TravelItineraryService(
            TravelPlanRepository travelPlanRepository,
            ItineraryDayRepository dayRepository,
            ItineraryItemRepository itemRepository,
            RouteSegmentRepository routeRepository,
            TravelItineraryMetadataStore metadataStore,
            PublicTransportRealtimeService realtimeService,
            KakaoPlaceSearchService kakaoPlaceSearchService
    ) {
        this.travelPlanRepository = travelPlanRepository;
        this.dayRepository = dayRepository;
        this.itemRepository = itemRepository;
        this.routeRepository = routeRepository;
        this.metadataStore = metadataStore;
        this.realtimeService = realtimeService;
        this.kakaoPlaceSearchService = kakaoPlaceSearchService;
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
        enrichMissingPlaceMetadata(plan, days);
        Map<Long, List<RouteSegment>> routesByDay = routes.stream()
                .collect(Collectors.groupingBy(route -> route.getFromItineraryItem().getItineraryDay().getId()));

        List<ItineraryResponse.ItineraryDayResponse> responseDays = days.stream()
                .map(day -> toDayResponse(day, routesByDay.getOrDefault(day.getId(), List.of())))
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
        // V1에서는 AI가 계산해 저장한 1차 경로를 유지하고, 완료 시 대중교통만 실시간 보강한다.
        List<RouteSegment> routes = routeRepository.findAllByTravelPlanId(travelPlanId).stream()
                .sorted(java.util.Comparator.comparingInt(RouteSegment::getOrder))
                .toList();
        return new RouteRecalculationResponse(
                travelPlanId,
                routes.stream().map(route -> ItineraryResponse.RouteSegmentResponse.from(
                        route, metadataStore.route(route.getId()))).toList()
        );
    }

    private ItineraryResponse.ItineraryDayResponse toDayResponse(ItineraryDay day, List<RouteSegment> routes) {
        List<ItineraryItem> items = itemRepository.findAllByItineraryDayIdOrderBySequenceAsc(day.getId());
        List<ItineraryResponse.ItineraryItemResponse> itemResponses = items.stream()
                .map(item -> ItineraryResponse.ItineraryItemResponse.from(item, metadataStore))
                .toList();
        List<ItineraryResponse.RouteSegmentResponse> routeResponses = routes.stream()
                .map(route -> ItineraryResponse.RouteSegmentResponse.from(route, metadataStore.route(route.getId())))
                .toList();
        return new ItineraryResponse.ItineraryDayResponse(
                day.getId(), day.getDayNumber(), day.getTravelDate(), itemResponses, routeResponses);
    }

    private void enrichMissingPlaceMetadata(TravelPlan plan, List<ItineraryDay> days) {
        for (ItineraryDay day : days) {
            for (ItineraryItem item : itemRepository.findAllByItineraryDayIdOrderBySequenceAsc(day.getId())) {
                if (metadataStore.place(item.getId()) != null) {
                    continue;
                }
                String providerPlaceId = item.getTravelPlanPlace().getPlace().getProviderPlaceId();
                try {
                    List<PlaceSearchItemResponse> candidates = kakaoPlaceSearchService
                            .search(plan.getRegion().getId(), providerPlaceId, 1, 15)
                            .places();
                    candidates.stream()
                            .filter(candidate -> providerPlaceId.equals(candidate.providerPlaceId()))
                            .findFirst()
                            .ifPresent(candidate -> metadataStore.putPlace(item.getId(),
                                    new TravelItineraryMetadataStore.PlaceMetadata(
                                            candidate.placeName(),
                                            candidate.roadAddress() == null || candidate.roadAddress().isBlank()
                                                    ? candidate.address() : candidate.roadAddress(),
                                            candidate.latitude(),
                                            candidate.longitude()
                                    )));
                } catch (RuntimeException ignored) {
                    // 장소 보강이 일시적으로 불가능해도 저장된 provider 식별자로 일정은 조회한다.
                }
            }
        }
    }

    private void refreshPublicRoutes(Long planId, ItineraryItem completedItem, LocalDateTime completedAt) {
        routeRepository.findAllByTravelPlanId(planId).stream()
                .filter(route -> route.getTransportType().name().equals("PUBLIC_TRANSPORT"))
                .filter(route -> route.getFromItineraryItem().getId().equals(completedItem.getId())
                        || route.getToItineraryItem().getId().equals(completedItem.getId()))
                .forEach(route -> realtimeService.refresh(route, completedAt));
    }

    private TravelPlan ownedPlan(Long userId, Long travelPlanId) {
        return travelPlanRepository.findByIdAndUserId(travelPlanId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }
}
