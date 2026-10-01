package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TagoBusArrivalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// 국토교통부 TAGO 정류장별 버스 도착정보 조회
@Component
public class TagoBusArrivalClient {

    private static final Logger log = LoggerFactory.getLogger(TagoBusArrivalClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final TagoBusArrivalProperties properties;

    public TagoBusArrivalClient(
            RestClient.Builder restClientBuilder,
            TagoBusArrivalProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public List<Arrival> findArrivals(String cityCode, String nodeId) {
        if (isBlank(cityCode) || isBlank(nodeId)) {
            return List.of();
        }
        if (properties.baseUrl().isBlank()) {
            throw new IllegalStateException("TAGO 버스 도착정보 API 주소가 설정되지 않았습니다.");
        }
        if (properties.serviceKey().isBlank()) {
            throw new IllegalStateException("TAGO 서비스 키가 설정되지 않았습니다.");
        }

        URI requestUri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("cityCode", cityCode)
                .queryParam("nodeId", nodeId)
                .queryParam("_type", "json")
                .build()
                .encode()
                .toUri();

        try {
            String responseBody = restClient.get()
                    .uri(requestUri)
                    .headers(headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)))
                    .retrieve()
                    .body(String.class);
            return parseResponse(responseBody);
        } catch (RestClientResponseException exception) {
            log.warn("TAGO 버스 도착정보 조회에 실패했습니다. status={}", exception.getStatusCode());
            throw new TagoBusArrivalException("TAGO 버스 도착정보 조회에 실패했습니다.", exception);
        } catch (RestClientException exception) {
            log.warn("TAGO 버스 도착정보 요청 중 오류가 발생했습니다.", exception);
            throw new TagoBusArrivalException("TAGO 버스 도착정보 요청 중 오류가 발생했습니다.", exception);
        }
    }

    private static List<Arrival> parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new TagoBusArrivalException("TAGO 버스 도착정보 응답 본문이 비어 있습니다.");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode header = root.path("response").path("header");
            String resultCode = text(header, "resultCode");
            if (resultCode != null && !"0".equals(resultCode) && !"00".equals(resultCode)) {
                throw new TagoBusArrivalException(
                        "TAGO 버스 도착정보 조회에 실패했습니다. code=" + resultCode
                                + ", message=" + text(header, "resultMsg")
                );
            }

            JsonNode itemNode = root.path("response").path("body").path("items").path("item");
            if (itemNode.isMissingNode() || itemNode.isNull() || itemNode.isTextual()) {
                return List.of();
            }

            List<Arrival> arrivals = new ArrayList<>();
            if (itemNode.isArray()) {
                itemNode.forEach(item -> addArrival(arrivals, item));
            } else if (itemNode.isObject()) {
                addArrival(arrivals, itemNode);
            }
            return List.copyOf(arrivals);
        } catch (TagoBusArrivalException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TagoBusArrivalException("TAGO 버스 도착정보 응답을 해석하지 못했습니다.", exception);
        }
    }

    private static void addArrival(List<Arrival> arrivals, JsonNode item) {
        Integer arrivalSeconds = nonNegativeInteger(item, "arrtime");
        if (arrivalSeconds == null) {
            return;
        }
        arrivals.add(new Arrival(
                text(item, "routeid"),
                text(item, "routeno"),
                arrivalSeconds,
                nonNegativeInteger(item, "arrprevstationcnt"),
                text(item, "vehicletp")
        ));
    }

    private static Integer nonNegativeInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            int parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Arrival(
            String routeId,
            String routeNumber,
            Integer arrivalSeconds,
            Integer remainingStops,
            String vehicleType
    ) {
    }

    public static class TagoBusArrivalException extends RuntimeException {

        public TagoBusArrivalException(String message) {
            super(message);
        }

        public TagoBusArrivalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
