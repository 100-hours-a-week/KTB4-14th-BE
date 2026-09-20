package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.KakaoMapProperties;
import com.audigo.domain.travel.dto.PlaceSearchItemResponse;
import com.audigo.domain.travel.dto.PlaceSearchResponse;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.repository.RegionRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class KakaoPlaceSearchService {

    private static final Logger log = LoggerFactory.getLogger(KakaoPlaceSearchService.class);
    private static final int MAX_PAGE_SIZE = 15;
    private static final int MAX_RESULT_COUNT = 45;

    private final RestClient restClient;
    private final KakaoMapProperties kakaoMapProperties;
    private final RegionRepository regionRepository;

    public KakaoPlaceSearchService(
            RestClient.Builder restClientBuilder,
            KakaoMapProperties kakaoMapProperties,
            RegionRepository regionRepository
    ) {
        this.restClient = restClientBuilder.build();
        this.kakaoMapProperties = kakaoMapProperties;
        this.regionRepository = regionRepository;
    }

    public PlaceSearchResponse search(Long regionId, String keyword, int page, int size) {
        Region region = regionRepository.findById(regionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REGION_NOT_FOUND));
        String normalizedKeyword = normalizeKeyword(keyword);
        validatePage(page, size);
        String restApiKey = kakaoMapProperties.restApiKey();

        if (restApiKey == null || restApiKey.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        try {
            KakaoSearchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("dapi.kakao.com")
                            .path("/v2/local/search/keyword.json")
                            .queryParam("query", region.getFullName() + " " + normalizedKeyword)
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(headers -> headers.set("Authorization", "KakaoAK " + restApiKey))
                    .retrieve()
                    .body(KakaoSearchResponse.class);

            if (response == null || response.documents() == null) {
                return new PlaceSearchResponse(List.of(), page, size, true, 0);
            }
            List<PlaceSearchItemResponse> places = response.documents().stream()
                    .map(this::toResponse)
                    .filter(Objects::nonNull)
                    .toList();
            KakaoMeta meta = response.meta();
            return new PlaceSearchResponse(
                    places,
                    page,
                    size,
                    meta == null || meta.isEnd(),
                    meta == null ? places.size() : meta.pageableCount()
            );
        } catch (RestClientResponseException exception) {
            log.warn(
                    "카카오 장소 검색에 실패했습니다. status={}, response={}",
                    exception.getStatusCode(),
                    exception.getResponseBodyAsString()
            );
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (RestClientException exception) {
            log.warn("카카오 장소 검색 요청 중 오류가 발생했습니다.", exception);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = keyword.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE
                || (long) (page - 1) * size >= MAX_RESULT_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private PlaceSearchItemResponse toResponse(KakaoPlaceDocument document) {
        BigDecimal longitude = parseCoordinate(document.longitude());
        BigDecimal latitude = parseCoordinate(document.latitude());
        if (document.id() == null || document.placeName() == null || longitude == null || latitude == null) {
            return null;
        }
        return new PlaceSearchItemResponse(
                PlaceProvider.KAKAO,
                document.id(),
                document.placeName(),
                document.addressName(),
                document.roadAddressName(),
                latitude,
                longitude
        );
    }

    private BigDecimal parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record KakaoSearchResponse(
            List<KakaoPlaceDocument> documents,
            KakaoMeta meta
    ) {
    }

    private record KakaoMeta(
            @JsonProperty("is_end") boolean isEnd,
            @JsonProperty("pageable_count") int pageableCount
    ) {
    }

    private record KakaoPlaceDocument(
            String id,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            String x,
            String y
    ) {
        String longitude() {
            return x;
        }

        String latitude() {
            return y;
        }
    }
}
