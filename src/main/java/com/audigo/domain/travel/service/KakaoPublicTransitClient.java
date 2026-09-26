package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.KakaoTransitProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오모빌리티 대중교통 통합 길찾기 API Client.
 *
 * <p>현재 시각을 출발 시각으로 전달해 카카오가 계산한 예상 도착시간을
 * 반환한다. 실제 차량 GPS나 정류장별 도착 알림을 조회하는 Client가 아니다.</p>
 */
@Component
public class KakaoPublicTransitClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoPublicTransitClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");
    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);
    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);

    private final RestClient restClient;
    private final KakaoTransitProperties properties;

    public KakaoPublicTransitClient(
            RestClient.Builder restClientBuilder,
            KakaoTransitProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public RouteResult findRoute(
            BigDecimal startLongitude,
            BigDecimal startLatitude,
            BigDecimal goalLongitude,
            BigDecimal goalLatitude,
            LocalDateTime departureAt,
            RouteType routeType
    ) {
        validateCoordinates(startLongitude, startLatitude, goalLongitude, goalLatitude);
        if (properties.baseUrl().isBlank()) {
            throw new IllegalStateException("카카오 대중교통 API 주소가 설정되지 않았습니다.");
        }
        if (properties.restApiKey().isBlank()) {
            throw new IllegalStateException("카카오 REST API 키가 설정되지 않았습니다.");
        }

        LocalDateTime requestedAt = departureAt == null ? LocalDateTime.now(KST) : departureAt;
        RouteType requestedRouteType = routeType == null ? RouteType.ALL : routeType;
        URI requestUri = buildRequestUri(
                startLongitude,
                startLatitude,
                goalLongitude,
                goalLatitude,
                requestedAt,
                requestedRouteType
        );

        try {
            String responseBody = restClient.get()
                    .uri(requestUri)
                    .headers(headers -> {
                        headers.set("Authorization", "KakaoAK " + properties.restApiKey());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .retrieve()
                    .body(String.class);
            return toRouteResult(parseResponse(responseBody), requestedAt);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "카카오 대중교통 경로 조회에 실패했습니다. status={}, response={}",
                    exception.getStatusCode(),
                    exception.getResponseBodyAsString()
            );
            throw new KakaoPublicTransitException("카카오 대중교통 경로 조회에 실패했습니다.", exception);
        } catch (RestClientException exception) {
            log.warn("카카오 대중교통 경로 요청 중 오류가 발생했습니다.", exception);
            throw new KakaoPublicTransitException("카카오 대중교통 경로 요청 중 오류가 발생했습니다.", exception);
        }
    }

    private static JsonNode parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new KakaoPublicTransitException("카카오 대중교통 응답 본문이 비어 있습니다.");
        }
        try {
            return OBJECT_MAPPER.readTree(responseBody);
        } catch (Exception exception) {
            throw new KakaoPublicTransitException("카카오 대중교통 응답을 해석하지 못했습니다.", exception);
        }
    }

    private URI buildRequestUri(
            BigDecimal startLongitude,
            BigDecimal startLatitude,
            BigDecimal goalLongitude,
            BigDecimal goalLatitude,
            LocalDateTime departureAt,
            RouteType routeType
    ) {
        return UriComponentsBuilder.fromUri(URI.create(properties.baseUrl()))
                .queryParam("start", coordinate(startLongitude, startLatitude))
                .queryParam("goal", coordinate(goalLongitude, goalLatitude))
                .queryParam("date", departureAt.format(DATE_FORMATTER))
                .queryParam("hhmmss", departureAt.format(TIME_FORMATTER))
                .queryParam("day_type", dayType(departureAt))
                .queryParam("route_type", routeType.value)
                .build()
                .encode()
                .toUri();
    }

    private RouteResult toRouteResult(JsonNode response, LocalDateTime departureAt) {
        if (response == null || !response.isObject()) {
            throw new KakaoPublicTransitException("카카오 대중교통 응답이 비어 있습니다.");
        }

        int resultCode = requiredInt(response, "result_code");
        if (resultCode != 0) {
            String message = text(response, "result_message");
            throw new KakaoPublicTransitException(
                    "카카오 대중교통 경로를 찾지 못했습니다. code=" + resultCode + ", message=" + message
            );
        }

        JsonNode journeys = response.get("journeys");
        if (journeys == null || !journeys.isArray() || journeys.isEmpty()) {
            throw new KakaoPublicTransitException("카카오 대중교통 경로 응답에 journey가 없습니다.");
        }

        JsonNode summary = journeys.get(0).get("summary");
        if (summary == null || !summary.isObject()) {
            throw new KakaoPublicTransitException("카카오 대중교통 경로 응답에 summary가 없습니다.");
        }

        int totalTimeSeconds = requiredNonNegativeInt(summary, "total_time");
        int waitingTimeSeconds = optionalNonNegativeInt(summary, "waiting_time");
        int distanceMeter = optionalNonNegativeInt(summary, "distance");
        int transferCount = optionalNonNegativeInt(summary, "transfer_count");

        return new RouteResult(
                text(response, "trans_id"),
                departureAt,
                departureAt.plusSeconds(totalTimeSeconds),
                totalTimeSeconds,
                waitingTimeSeconds,
                distanceMeter,
                transferCount
        );
    }

    private static void validateCoordinates(
            BigDecimal startLongitude,
            BigDecimal startLatitude,
            BigDecimal goalLongitude,
            BigDecimal goalLatitude
    ) {
        if (!isValidLongitude(startLongitude)
                || !isValidLatitude(startLatitude)
                || !isValidLongitude(goalLongitude)
                || !isValidLatitude(goalLatitude)) {
            throw new IllegalArgumentException("카카오 대중교통 경로 조회 좌표가 올바르지 않습니다.");
        }
    }

    private static boolean isValidLongitude(BigDecimal value) {
        return value != null && value.compareTo(MIN_LONGITUDE) >= 0 && value.compareTo(MAX_LONGITUDE) <= 0;
    }

    private static boolean isValidLatitude(BigDecimal value) {
        return value != null && value.compareTo(MIN_LATITUDE) >= 0 && value.compareTo(MAX_LATITUDE) <= 0;
    }

    private static String coordinate(BigDecimal longitude, BigDecimal latitude) {
        return longitude.toPlainString() + "," + latitude.toPlainString();
    }

    private static String dayType(LocalDateTime departureAt) {
        DayOfWeek dayOfWeek = departureAt.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return "Saturday";
        }
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            return "Holiday";
        }
        return "Weekday";
    }

    private static int requiredInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.canConvertToInt()) {
            throw new KakaoPublicTransitException("카카오 응답의 필수 숫자 필드가 없습니다. field=" + fieldName);
        }
        return value.intValue();
    }

    private static int requiredNonNegativeInt(JsonNode node, String fieldName) {
        int value = requiredInt(node, fieldName);
        if (value < 0) {
            throw new KakaoPublicTransitException("카카오 응답의 숫자 필드가 음수입니다. field=" + fieldName);
        }
        return value;
    }

    private static int optionalNonNegativeInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.canConvertToInt() || value.intValue() < 0) {
            throw new KakaoPublicTransitException("카카오 응답의 숫자 필드가 올바르지 않습니다. field=" + fieldName);
        }
        return value.intValue();
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    public enum RouteType {
        ALL("All"),
        SUBWAY("Subway"),
        BUS("Bus");

        private final String value;

        RouteType(String value) {
            this.value = value;
        }
    }

    public record RouteResult(
            String transactionId,
            LocalDateTime requestedDepartureAt,
            LocalDateTime estimatedArrivalAt,
            int totalTimeSeconds,
            int waitingTimeSeconds,
            int distanceMeter,
            int transferCount
    ) {
    }

    public static class KakaoPublicTransitException extends RuntimeException {

        public KakaoPublicTransitException(String message) {
            super(message);
        }

        public KakaoPublicTransitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
