package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TagoBusStopProperties;
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

/**
 * 국토교통부 TAGO 버스정류소정보 API 호출
 */
@Component
public class TagoBusStopClient {

    private static final Logger log = LoggerFactory.getLogger(TagoBusStopClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CITY_CODE_OPERATION = "getCtyCodeList";
    private static final String STOP_NAME_OPERATION = "getSttnNoList";
    private static final int NUM_OF_ROWS = 100;

    private final RestClient restClient;
    private final TagoBusStopProperties properties;

    public TagoBusStopClient(
            RestClient.Builder restClientBuilder,
            TagoBusStopProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public List<City> findCities() {
        return request(CITY_CODE_OPERATION).stream()
                .map(item -> new City(
                        text(item, "citycode", "cityCode"),
                        text(item, "cityname", "cityName")
                ))
                .filter(city -> !isBlank(city.cityCode()) && !isBlank(city.cityName()))
                .toList();
    }

    public List<Stop> findStopsByName(String cityCode, String stopName) {
        if (isBlank(cityCode) || isBlank(stopName)) {
            return List.of();
        }
        return request(STOP_NAME_OPERATION, cityCode, stopName).stream()
                .map(item -> new Stop(
                        text(item, "citycode", "cityCode"),
                        text(item, "nodeid", "nodeId"),
                        text(item, "nodenm", "nodeName"),
                        text(item, "nodeno", "nodeNo")
                ))
                .filter(stop -> !isBlank(stop.nodeId()) && !isBlank(stop.nodeName()))
                .toList();
    }

    private List<JsonNode> request(String operation, String... stopQuery) {
        if (properties.baseUrl().isBlank()) {
            throw new IllegalStateException("TAGO 버스정류소정보 API 주소가 설정되지 않았습니다.");
        }
        if (properties.serviceKey().isBlank()) {
            throw new IllegalStateException("TAGO 서비스 키가 설정되지 않았습니다.");
        }

        List<JsonNode> items = new ArrayList<>();
        int pageNo = 1;
        while (true) {
            Page page = requestPage(operation, pageNo, stopQuery);
            items.addAll(page.items());
            if (page.totalCount() != null) {
                if (items.size() > page.totalCount()) {
                    throw new TagoBusStopException("TAGO 버스정류소정보 응답 건수가 전체 건수보다 많습니다.");
                }
                if (items.size() == page.totalCount()) {
                    return List.copyOf(items);
                }
                if (page.items().isEmpty()) {
                    throw new TagoBusStopException("TAGO 버스정류소정보 조회 중 페이지가 누락되었습니다.");
                }
            } else if (page.items().size() < NUM_OF_ROWS) {
                if (items.isEmpty()) {
                    throw new TagoBusStopException("TAGO 버스정류소정보의 빈 응답을 검증할 수 없습니다.");
                }
                return List.copyOf(items);
            }
            pageNo++;
        }
    }

    private Page requestPage(String operation, int pageNo, String... stopQuery) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(endpoint(operation))
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", NUM_OF_ROWS)
                .queryParam("_type", "json");
        if (stopQuery.length > 0) {
            uriBuilder.queryParam("cityCode", stopQuery[0]);
            uriBuilder.queryParam("nodeNm", stopQuery[1]);
        }
        URI requestUri = uriBuilder.build().encode().toUri();

        try {
            String responseBody = restClient.get()
                    .uri(requestUri)
                    .headers(headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)))
                    .retrieve()
                    .body(String.class);
            return parsePage(responseBody);
        } catch (RestClientResponseException exception) {
            log.warn("TAGO 버스정류소정보 조회에 실패했습니다. status={}", exception.getStatusCode());
            throw new TagoBusStopException("TAGO 버스정류소정보 조회에 실패했습니다.", exception);
        } catch (RestClientException exception) {
            log.warn("TAGO 버스정류소정보 요청 중 오류가 발생했습니다.", exception);
            throw new TagoBusStopException("TAGO 버스정류소정보 요청 중 오류가 발생했습니다.", exception);
        }
    }

    private String endpoint(String operation) {
        String separator = properties.baseUrl().endsWith("/") ? "" : "/";
        return properties.baseUrl() + separator + operation;
    }

    private static Page parsePage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new TagoBusStopException("TAGO 버스정류소정보 응답 본문이 비어 있습니다.");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode header = root.path("response").path("header");
            String resultCode = text(header, "resultCode");
            if (!"0".equals(resultCode) && !"00".equals(resultCode)) {
                throw new TagoBusStopException(
                        "TAGO 버스정류소정보 조회에 실패했습니다. code=" + resultCode
                                + ", message=" + text(header, "resultMsg")
                );
            }

            JsonNode body = root.path("response").path("body");
            if (!body.isObject()) {
                throw new TagoBusStopException("TAGO 버스정류소정보 응답 본문 형식이 올바르지 않습니다.");
            }
            Integer totalCount = null;
            JsonNode totalCountNode = body.path("totalCount");
            if (!totalCountNode.isMissingNode() && !totalCountNode.isNull()) {
                if (!totalCountNode.canConvertToInt() && !totalCountNode.isTextual()) {
                    throw new TagoBusStopException("TAGO 버스정류소정보 전체 건수가 올바르지 않습니다.");
                }
                try {
                    totalCount = Integer.parseInt(totalCountNode.asText());
                } catch (NumberFormatException exception) {
                    throw new TagoBusStopException("TAGO 버스정류소정보 전체 건수가 올바르지 않습니다.", exception);
                }
                if (totalCount < 0) {
                    throw new TagoBusStopException("TAGO 버스정류소정보 전체 건수가 올바르지 않습니다.");
                }
            }

            JsonNode itemNode = root.path("response").path("body").path("items").path("item");
            if (itemNode.isMissingNode() || itemNode.isNull() || itemNode.isTextual()) {
                if (totalCount != null && totalCount == 0) {
                    return new Page(List.of(), 0);
                }
                throw new TagoBusStopException("TAGO 버스정류소정보 조회 결과를 확인할 수 없습니다.");
            }

            List<JsonNode> items = new ArrayList<>();
            if (itemNode.isArray()) {
                itemNode.forEach(items::add);
            } else if (itemNode.isObject()) {
                items.add(itemNode);
            }
            return new Page(List.copyOf(items), totalCount);
        } catch (TagoBusStopException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TagoBusStopException("TAGO 버스정류소정보 응답을 해석하지 못했습니다.", exception);
        }
    }

    private record Page(List<JsonNode> items, Integer totalCount) {
    }

    private static String text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record City(String cityCode, String cityName) {
    }

    public record Stop(String cityCode, String nodeId, String nodeName, String stationNumber) {
    }

    public static class TagoBusStopException extends RuntimeException {

        public TagoBusStopException(String message) {
            super(message);
        }

        public TagoBusStopException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
