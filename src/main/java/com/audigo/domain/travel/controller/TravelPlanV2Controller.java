package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.TravelPlanCreatedResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.service.TravelGenerationV2Service;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/travel-plans")
@ConditionalOnProperty(prefix = "audigo.sqs", name = "enabled", havingValue = "true")
public class TravelPlanV2Controller {

    private final CurrentUser currentUser;
    private final TravelGenerationV2Service generationService;

    public TravelPlanV2Controller(
            CurrentUser currentUser,
            TravelGenerationV2Service generationService
    ) {
        this.currentUser = currentUser;
        this.generationService = generationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TravelPlanCreatedResponse> createTravelPlan(
            @Valid @RequestBody TravelPlanRequest request
    ) {
        return ApiResponse.of(
                "여행 생성 요청",
                generationService.create(currentUser.id(), request)
        );
    }

    @PostMapping({"/{travelPlanId}/regenerate", "/{travelPlanId}/regeneration"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TravelPlanCreatedResponse> regenerate(
            @PathVariable Long travelPlanId,
            @Valid @RequestBody(required = false) TravelPlanRequest request
    ) {
        return ApiResponse.of(
                "여행 재생성 요청",
                generationService.regenerate(currentUser.id(), travelPlanId, request)
        );
    }
}
