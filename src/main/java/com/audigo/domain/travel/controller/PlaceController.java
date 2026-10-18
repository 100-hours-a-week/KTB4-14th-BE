package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.dto.LivePlaceResponse;
import com.audigo.domain.travel.service.KakaoPlaceSearchService;
import com.audigo.domain.travel.service.KakaoPlaceLiveLookupService;
import com.audigo.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final KakaoPlaceSearchService kakaoPlaceSearchService;
    private final KakaoPlaceLiveLookupService kakaoPlaceLiveLookupService;

    public PlaceController(
            KakaoPlaceSearchService kakaoPlaceSearchService,
            KakaoPlaceLiveLookupService kakaoPlaceLiveLookupService
    ) {
        this.kakaoPlaceSearchService = kakaoPlaceSearchService;
        this.kakaoPlaceLiveLookupService = kakaoPlaceLiveLookupService;
    }

    @GetMapping("/search")
    public PlaceSearchResponse search(
            @RequestParam(name = "region_id") Long regionId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "15") int size
    ) {
        return kakaoPlaceSearchService.search(regionId, keyword, page, size);
    }

    @GetMapping("/{provider}/{providerPlaceId}/live")
    public ApiResponse<LivePlaceResponse> lookupLive(
            @PathVariable String provider,
            @PathVariable String providerPlaceId
    ) {
        return ApiResponse.of(
                "장소 실시간 조회",
                kakaoPlaceLiveLookupService.lookup(provider, providerPlaceId)
        );
    }
}
