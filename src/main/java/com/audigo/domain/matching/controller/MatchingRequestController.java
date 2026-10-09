package com.audigo.domain.matching.controller;

import com.audigo.domain.matching.dto.CreateMatchingRequestRequest;
import com.audigo.domain.matching.dto.MatchingRequestResponse;
import com.audigo.domain.matching.service.MatchingRequestService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/matching-requests")
public class MatchingRequestController {

    private final CurrentUser currentUser;
    private final MatchingRequestService matchingRequestService;

    public MatchingRequestController(CurrentUser currentUser, MatchingRequestService matchingRequestService) {
        this.currentUser = currentUser;
        this.matchingRequestService = matchingRequestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MatchingRequestResponse> create(
            @Valid @RequestBody CreateMatchingRequestRequest request
    ) {
        return ApiResponse.of(
                "request_success",
                matchingRequestService.create(currentUser.id(), request)
        );
    }

    @GetMapping("/me")
    public ApiResponse<MatchingRequestResponse> getMine() {
        return ApiResponse.of(
                "matching_request_loading_success",
                matchingRequestService.getMine(currentUser.id())
        );
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelMine() {
        matchingRequestService.cancelMine(currentUser.id());
    }
}
