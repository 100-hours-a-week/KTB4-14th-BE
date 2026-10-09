package com.audigo.domain.matching.controller;

import com.audigo.domain.matching.dto.MatchingProfileResponse;
import com.audigo.domain.matching.dto.UpdateMatchingProfileRequest;
import com.audigo.domain.matching.service.MatchingProfileService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/matching-profiles")
public class MatchingProfileController {

    private final CurrentUser currentUser;
    private final MatchingProfileService matchingProfileService;

    public MatchingProfileController(CurrentUser currentUser, MatchingProfileService matchingProfileService) {
        this.currentUser = currentUser;
        this.matchingProfileService = matchingProfileService;
    }

    @GetMapping("/me")
    public ApiResponse<MatchingProfileResponse> getMyProfile() {
        return ApiResponse.of(
                "matching_profile_found",
                matchingProfileService.getMyProfile(currentUser.id())
        );
    }

    @PutMapping("/me")
    public ApiResponse<MatchingProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateMatchingProfileRequest request
    ) {
        return ApiResponse.of(
                "matching_profile_updated",
                matchingProfileService.updateMyProfile(currentUser.id(), request)
        );
    }
}
