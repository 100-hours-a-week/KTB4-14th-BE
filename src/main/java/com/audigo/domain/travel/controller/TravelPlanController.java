package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.TravelPlanCreatedResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.service.TravelPlanService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/travel-plans")
public class TravelPlanController {

    private final CurrentUser currentUser;
    private final TravelPlanService travelPlanService;

    public TravelPlanController(CurrentUser currentUser, TravelPlanService travelPlanService) {
        this.currentUser = currentUser;
        this.travelPlanService = travelPlanService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TravelPlanCreatedResponse> createTravelPlan(
            @Valid @RequestBody TravelPlanRequest request
    ) {
        TravelPlan travelPlan = travelPlanService.createTravelPlan(currentUser.id(), request);
        return ApiResponse.of(
                "여행 생성 요청",
                TravelPlanCreatedResponse.from(travelPlan)
        );
    }
}
