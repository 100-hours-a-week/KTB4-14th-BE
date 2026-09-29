package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.KakaoMapProperties;
import com.audigo.domain.travel.dto.LivePlaceResponse;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class KakaoPlaceLiveLookupService {

    private static final Logger log = LoggerFactory.getLogger(KakaoPlaceLiveLookupService.class);
    private static final int MAX_PROVIDER_PLACE_ID_LENGTH = 100;
    private static final String KAKAO_MAP_NAME = "카카오맵";
    private static final String KAKAO_MAP_NAME_EN = "kakao map";

    private final RestClient restClient;
    private final KakaoMapProperties properties;

    public KakaoPlaceLiveLookupService(KakaoMapProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    public LivePlaceResponse lookup(String providerValue, String providerPlaceId) {
        PlaceProvider provider = parseProvider(providerValue);
        String normalizedPlaceId = normalizePlaceId(providerPlaceId);
        URI placeUri = buildPlaceUri(normalizedPlaceId);
        String placeUrl = placeUri.toString();

        String placeName = null;
        try {
            String html = restClient.get()
                    .uri(placeUri)
                    .headers(headers -> {
                        headers.setAccept(java.util.List.of(MediaType.TEXT_HTML));
                        headers.set(HttpHeaders.USER_AGENT, "Audigo/1.0");
                    })
                    .retrieve()
                    .body(String.class);
            placeName = extractPlaceName(html);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "카카오 장소 상세 페이지 조회에 실패했습니다. provider={} placeId={} status={}",
                    provider,
                    normalizedPlaceId,
                    exception.getStatusCode()
            );
        } catch (RestClientException exception) {
            log.warn(
                    "카카오 장소 상세 페이지 요청 중 오류가 발생했습니다. provider={} placeId={}",
                    provider,
                    normalizedPlaceId,
                    exception
            );
        }

        return new LivePlaceResponse(
                provider.name(),
                normalizedPlaceId,
                placeName,
                placeUrl
        );
    }

    static String extractPlaceName(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        Document document = Jsoup.parse(html);
        String name = metaContent(document, "meta[property=og:title]");
        if (isUsablePlaceName(name)) {
            return cleanPlaceName(name);
        }

        name = metaContent(document, "meta[name=twitter:title]");
        if (isUsablePlaceName(name)) {
            return cleanPlaceName(name);
        }

        name = document.title();
        return isUsablePlaceName(name) ? cleanPlaceName(name) : null;
    }

    private static String metaContent(Document document, String selector) {
        Element element = document.select(selector).first();
        if (element == null) {
            return null;
        }
        String content = element.attr("content");
        return content == null || content.isBlank() ? null : content.trim();
    }

    private static boolean isUsablePlaceName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals(KAKAO_MAP_NAME.toLowerCase(Locale.ROOT))
                && !normalized.equals(KAKAO_MAP_NAME_EN);
    }

    private static String cleanPlaceName(String value) {
        String cleaned = value.trim()
                .replaceFirst("\\s*[|·-]\\s*카카오맵\\s*$", "")
                .replaceFirst("\\s*[|·-]\\s*Kakao Map\\s*$", "")
                .trim();
        return isUsablePlaceName(cleaned) ? cleaned : null;
    }

    private PlaceProvider parseProvider(String providerValue) {
        if (providerValue == null || providerValue.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return PlaceProvider.valueOf(providerValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalizePlaceId(String providerPlaceId) {
        if (providerPlaceId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = providerPlaceId.trim();
        if (normalized.isBlank()
                || normalized.length() > MAX_PROVIDER_PLACE_ID_LENGTH
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("?")
                || normalized.contains("#")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private URI buildPlaceUri(String providerPlaceId) {
        String baseUrl = properties.placeDetailBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return UriComponentsBuilder.fromUriString(baseUrl.trim())
                .pathSegment(providerPlaceId)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }
}
