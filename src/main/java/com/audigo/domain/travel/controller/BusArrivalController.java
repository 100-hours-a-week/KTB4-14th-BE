package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.BusArrivalLookupResponse;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.domain.travel.service.BusArrivalQueryService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusArrivalController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CurrentUser currentUser;
    private final TravelPlanRepository travelPlanRepository;
    private final RouteSegmentRepository routeSegmentRepository;
    private final BusArrivalQueryService busArrivalQueryService;

    public BusArrivalController(
            CurrentUser currentUser,
            TravelPlanRepository travelPlanRepository,
            RouteSegmentRepository routeSegmentRepository,
            BusArrivalQueryService busArrivalQueryService
    ) {
        this.currentUser = currentUser;
        this.travelPlanRepository = travelPlanRepository;
        this.routeSegmentRepository = routeSegmentRepository;
        this.busArrivalQueryService = busArrivalQueryService;
    }

    @Transactional(readOnly = true)
    @GetMapping("/api/travel-plans/{travelPlanId}/routes/{routeSegmentId}/bus-arrivals")
    public ApiResponse<BusArrivalLookupResponse> getBusArrivals(
            @PathVariable Long travelPlanId,
            @PathVariable Long routeSegmentId,
            @RequestParam(name = "city_code", required = false) String cityCode,
            @RequestParam(name = "node_id", required = false) String nodeId
    ) {
        travelPlanRepository.findByIdAndUserId(travelPlanId, currentUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        RouteSegment route = routeSegmentRepository.findById(routeSegmentId)
                .filter(value -> value.getTravelPlan().getId().equals(travelPlanId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        return ApiResponse.of(
                "버스 도착정보 조회",
                busArrivalQueryService.findForRoute(
                        route,
                        cityCode,
                        nodeId,
                        LocalDateTime.now(KST)
                )
        );
    }
}
