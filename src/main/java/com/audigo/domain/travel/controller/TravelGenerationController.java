package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.TravelGenerationStatusResponse;
import com.audigo.domain.travel.service.TravelGenerationOrchestrator;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-generation-jobs")
public class TravelGenerationController {

    private final CurrentUser currentUser;
    private final TravelGenerationOrchestrator generationOrchestrator;

    public TravelGenerationController(CurrentUser currentUser, TravelGenerationOrchestrator generationOrchestrator) {
        this.currentUser = currentUser;
        this.generationOrchestrator = generationOrchestrator;
    }

    @GetMapping("/{generationJobId}")
    public ApiResponse<TravelGenerationStatusResponse> getStatus(@PathVariable Long generationJobId) {
        return ApiResponse.of(
                "여행 생성 상태 조회",
                generationOrchestrator.statusByJob(currentUser.id(), generationJobId)
        );
    }
}
