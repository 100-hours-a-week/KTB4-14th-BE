package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.KakaoMapProperties;
import com.audigo.domain.travel.dto.LivePlaceResponse;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
    private static final Pattern X_THEN_Y_PATTERN = Pattern.compile(
            "(?is)[\\\"']?(?:x|longitude|lng|lon)[\\\"']?\\s*[:=]\\s*[\\\"']?"
                    + "([+-]?\\d+(?:\\.\\d+)?)[\\\"']?[^{}]{0,500}"
                    + "[\\\"']?(?:y|latitude|lat)[\\\"']?\\s*[:=]\\s*[\\\"']?"
                    + "([+-]?\\d+(?:\\.\\d+)?)[\\\"']?"
    );
    private static final Pattern Y_THEN_X_PATTERN = Pattern.compile(
            "(?is)[\\\"']?(?:y|latitude|lat)[\\\"']?\\s*[:=]\\s*[\\\"']?"
                    + "([+-]?\\d+(?:\\.\\d+)?)[\\\"']?[^{}]{0,500}"
                    + "[\\\"']?(?:x|longitude|lng|lon)[\\\"']?\\s*[:=]\\s*[\\\"']?"
                    + "([+-]?\\d+(?:\\.\\d+)?)[\\\"']?"
    );

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
        Coordinates coordinates = null;
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
            coordinates = extractCoordinates(html);
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
                coordinates == null ? null : coordinates.latitude(),
                coordinates == null ? null : coordinates.longitude(),
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

    static Coordinates extractCoordinates(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        Document document = Jsoup.parse(html);
        Coordinates coordinates = coordinatesFromMeta(document);
        if (coordinates != null) {
            return coordinates;
        }

        coordinates = coordinatesFromDataAttributes(document);
        if (coordinates != null) {
            return coordinates;
        }

        Matcher matcher = X_THEN_Y_PATTERN.matcher(html);
        if (matcher.find()) {
            return coordinates(matcher.group(2), matcher.group(1));
        }

        matcher = Y_THEN_X_PATTERN.matcher(html);
        if (matcher.find()) {
            return coordinates(matcher.group(1), matcher.group(2));
        }
        return null;
    }

    private static Coordinates coordinatesFromMeta(Document document) {
        String latitude = null;
        String longitude = null;
        for (Element meta : document.select("meta")) {
            String key = meta.attr("property");
            if (key == null || key.isBlank()) {
                key = meta.attr("name");
            }
            String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
            if (normalizedKey.equals("twitter:image") || normalizedKey.equals("og:image")) {
                Coordinates coordinates = coordinatesFromStaticMapUrl(meta.attr("content"));
                if (coordinates != null) {
                    return coordinates;
                }
                continue;
            }
            if (normalizedKey.equals("place:location:latitude")
                    || normalizedKey.equals("latitude")
                    || normalizedKey.equals("lat")) {
                latitude = meta.attr("content");
            } else if (normalizedKey.equals("place:location:longitude")
                    || normalizedKey.equals("longitude")
                    || normalizedKey.equals("lng")
                    || normalizedKey.equals("lon")) {
                longitude = meta.attr("content");
            }
        }
        return coordinates(latitude, longitude);
    }

    private static Coordinates coordinatesFromStaticMapUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        try {
            String marker = UriComponentsBuilder.fromUriString(imageUrl.trim())
                    .build()
                    .getQueryParams()
                    .getFirst("m");
            if (marker == null || marker.isBlank()) {
                return null;
            }
            marker = URLDecoder.decode(marker, StandardCharsets.UTF_8);
            String[] values = marker.split(",", -1);
            if (values.length != 2) {
                return null;
            }
            // Kakao static map의 m 파라미터는 longitude,latitude 순서다.
            return coordinates(values[1], values[0]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Coordinates coordinatesFromDataAttributes(Document document) {
        Elements elements = document.select("[data-x][data-y], [data-longitude][data-latitude], [data-lng][data-lat]");
        for (Element element : elements) {
            String longitude = firstNonBlank(
                    element.attr("data-x"),
                    element.attr("data-longitude"),
                    element.attr("data-lng")
            );
            String latitude = firstNonBlank(
                    element.attr("data-y"),
                    element.attr("data-latitude"),
                    element.attr("data-lat")
            );
            Coordinates coordinates = coordinates(latitude, longitude);
            if (coordinates != null) {
                return coordinates;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Coordinates coordinates(String latitudeValue, String longitudeValue) {
        BigDecimal latitude = parseCoordinate(latitudeValue, -90, 90);
        BigDecimal longitude = parseCoordinate(longitudeValue, -180, 180);
        if (latitude == null || longitude == null) {
            return null;
        }
        return new Coordinates(latitude, longitude);
    }

    private static BigDecimal parseCoordinate(String value, int min, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal coordinate = new BigDecimal(value.trim());
            return coordinate.compareTo(BigDecimal.valueOf(min)) >= 0
                    && coordinate.compareTo(BigDecimal.valueOf(max)) <= 0
                    ? coordinate
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    record Coordinates(BigDecimal latitude, BigDecimal longitude) {
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
