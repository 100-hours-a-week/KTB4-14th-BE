package com.audigo.domain.matching.controller;

import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.service.MatchingCandidateService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/matches")
public class MatchingCandidateController {

    private final CurrentUser currentUser;
    private final MatchingCandidateService matchingCandidateService;

    public MatchingCandidateController(
            CurrentUser currentUser,
            MatchingCandidateService matchingCandidateService
    ) {
        this.currentUser = currentUser;
        this.matchingCandidateService = matchingCandidateService;
    }

    @GetMapping("/candidates")
    public ApiResponse<MatchingCandidatesResponse> getCandidates() {
        return ApiResponse.of(
                "get_match_candidates_success",
                matchingCandidateService.getCandidates(currentUser.id())
        );
    }
}
