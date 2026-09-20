package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.TravelPlanCreatedResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.service.TravelPlanService;
import com.audigo.domain.travel.service.TravelGenerationOrchestrator;
import com.audigo.domain.travel.dto.TravelGenerationStatusResponse;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/travel-plans")
public class TravelPlanController {

    private final CurrentUser currentUser;
    private final TravelPlanService travelPlanService;
    private final TravelGenerationOrchestrator generationOrchestrator;

    public TravelPlanController(
            CurrentUser currentUser,
            TravelPlanService travelPlanService,
            TravelGenerationOrchestrator generationOrchestrator
    ) {
        this.currentUser = currentUser;
        this.travelPlanService = travelPlanService;
        this.generationOrchestrator = generationOrchestrator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TravelPlanCreatedResponse> createTravelPlan(
            @Valid @RequestBody TravelPlanRequest request
    ) {
        TravelPlan travelPlan = travelPlanService.createTravelPlan(currentUser.id(), request);
        Long generationJobId = generationOrchestrator.schedule(currentUser.id(), travelPlan.getId(), request);
        return ApiResponse.of(
                "여행 생성 요청",
                TravelPlanCreatedResponse.from(travelPlan, generationJobId)
        );
    }

    @GetMapping("/{travelPlanId}/status")
    public ApiResponse<TravelGenerationStatusResponse> getGenerationStatus(
            @PathVariable Long travelPlanId
    ) {
        return ApiResponse.of(
                "여행 생성 상태 조회",
                generationOrchestrator.status(currentUser.id(), travelPlanId)
        );
    }

    @PostMapping("/{travelPlanId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TravelPlanCreatedResponse> regenerate(
            @PathVariable Long travelPlanId
    ) {
        TravelPlan travelPlan = travelPlanService.regenerateTravelPlan(currentUser.id(), travelPlanId);
        Long generationJobId = generationOrchestrator.schedule(currentUser.id(), travelPlanId, null);
        return ApiResponse.of(
                "여행 재생성 요청",
                TravelPlanCreatedResponse.from(travelPlan, generationJobId)
        );
    }
}
