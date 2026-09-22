package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.ItineraryCompletionRequest;
import com.audigo.domain.travel.dto.ItineraryCompletionResponse;
import com.audigo.domain.travel.dto.ItineraryResponse;
import com.audigo.domain.travel.dto.RouteRecalculationResponse;
import com.audigo.domain.travel.service.TravelItineraryService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TravelItineraryController {

    private final CurrentUser currentUser;
    private final TravelItineraryService itineraryService;

    public TravelItineraryController(CurrentUser currentUser, TravelItineraryService itineraryService) {
        this.currentUser = currentUser;
        this.itineraryService = itineraryService;
    }

    @GetMapping("/api/travel-plans/{travelPlanId}/itinerary")
    public ApiResponse<ItineraryResponse> getItinerary(@PathVariable Long travelPlanId) {
        return ApiResponse.of(
                "여행 일정 조회",
                itineraryService.getItinerary(currentUser.id(), travelPlanId)
        );
    }

    @PatchMapping("/api/itinerary-items/{itineraryItemId}/completion")
    public ApiResponse<ItineraryCompletionResponse> updateCompletion(
            @PathVariable Long itineraryItemId,
            @Valid @RequestBody ItineraryCompletionRequest request
    ) {
        return ApiResponse.of(
                "일정 완료 상태 변경",
                itineraryService.updateCompletion(currentUser.id(), itineraryItemId, request.isCompleted())
        );
    }

    @PostMapping("/api/travel-plans/{travelPlanId}/routes/recalculate")
    public ApiResponse<RouteRecalculationResponse> recalculateRoutes(@PathVariable Long travelPlanId) {
        return ApiResponse.of(
                "이동 경로 조회",
                itineraryService.recalculateRoutes(currentUser.id(), travelPlanId)
        );
    }
}
