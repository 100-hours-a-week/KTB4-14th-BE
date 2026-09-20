package com.audigo.domain.travel.controller;

import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.service.KakaoPlaceSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final KakaoPlaceSearchService kakaoPlaceSearchService;

    public PlaceController(KakaoPlaceSearchService kakaoPlaceSearchService) {
        this.kakaoPlaceSearchService = kakaoPlaceSearchService;
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
}
