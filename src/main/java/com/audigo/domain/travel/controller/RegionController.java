package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.RegionResponse;
import com.audigo.domain.travel.service.TravelPlanService;
import com.audigo.global.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/regions")
public class RegionController {

    private final TravelPlanService travelPlanService;

    public RegionController(TravelPlanService travelPlanService) {
        this.travelPlanService = travelPlanService;
    }

    @GetMapping
    public ApiResponse<List<RegionResponse>> getRegions() {
        return ApiResponse.of("regions_found", travelPlanService.getRegions());
    }
}
