package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 대중교통 도착 정보 어댑터.
 *
 * <p>실시간 정보는 메타데이터 저장소에만 보관한다. 외부 API가 실패해도
 * AI가 계산한 최초 경로 정보가 사라지지 않도록 기존 값을 그대로 유지한다.</p>
 */
@Service
public class PublicTransportRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(PublicTransportRealtimeService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final TravelItineraryMetadataStore metadataStore;
    private final String baseUrl;

    public PublicTransportRealtimeService(
            RestClient.Builder restClientBuilder,
            TravelItineraryMetadataStore metadataStore,
            @Value("${AUDIGO_TRANSIT_BASE_URL:${audigo.transit.base-url:}}") String baseUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.metadataStore = metadataStore;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public void refresh(RouteSegment route, LocalDateTime completedAt) {
        if (route == null || route.getTransportType() != TravelTransportType.PUBLIC_TRANSPORT) {
            return;
        }

        TravelItineraryMetadataStore.RouteMetadata current = metadataStore.route(route.getId());
        if (current == null) {
            current = new TravelItineraryMetadataStore.RouteMetadata(
                    null, null, null, null, null, false, null);
        }
        if (baseUrl.isBlank()) {
            log.warn("대중교통 API 주소가 설정되지 않아 AI 계산값을 유지합니다. routeId={}", route.getId());
            metadataStore.putRoute(route.getId(), withoutRealtime(current));
            return;
        }
        final TravelItineraryMetadataStore.RouteMetadata currentMetadata = current;
        LocalDateTime requestAt = completedAt == null ? LocalDateTime.now() : completedAt;

        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUri(URI.create(baseUrl));
            addQueryParam(uriBuilder, "line", currentMetadata.lineName());
            addQueryParam(uriBuilder, "vehicle", currentMetadata.vehicleNumber());
            uriBuilder.queryParam("at", requestAt);
            URI requestUri = uriBuilder.build().encode().toUri();
            String responseBody = restClient.get()
                    .uri(requestUri)
                    .retrieve()
                    .body(String.class);
            JsonNode response = parseResponse(responseBody);
            JsonNode result = unwrapResult(response);
            Integer nextArrival = integer(result,
                    "next_arrival_minutes", "nextArrivalMinutes", "arrival_minutes", "arrivalMinutes");
            LocalDateTime departure = dateTime(result, "estimated_departure_at", "estimatedDepartureAt");
            LocalDateTime arrival = dateTime(result, "estimated_arrival_at", "estimatedArrivalAt");
            Optional<String> lineName = text(result, "line_name", "lineName", "line");
            Optional<String> vehicleNumber = text(result,
                    "vehicle_number", "vehicleNumber", "bus_number", "vehicle");
            if (result == null || (nextArrival == null && departure == null && arrival == null
                    && lineName.isEmpty() && vehicleNumber.isEmpty())) {
                throw new IllegalStateException("대중교통 API가 갱신할 정보를 보내지 않았습니다.");
            }
            metadataStore.putRoute(route.getId(), new TravelItineraryMetadataStore.RouteMetadata(
                    lineName.orElse(currentMetadata.lineName()),
                    vehicleNumber.orElse(currentMetadata.vehicleNumber()),
                    nextArrival == null ? currentMetadata.nextArrivalMinutes() : nextArrival,
                    departure == null ? currentMetadata.estimatedDepartureAt() : departure,
                    arrival == null ? currentMetadata.estimatedArrivalAt() : arrival,
                    true,
                    LocalDateTime.now()
            ));
        } catch (RuntimeException exception) {
            log.warn("대중교통 실시간 정보 갱신에 실패해 AI 계산값을 유지합니다. routeId={}, 완료 시각={}",
                    route.getId(), requestAt, exception);
            metadataStore.putRoute(route.getId(), withoutRealtime(currentMetadata));
        }
    }

    private static TravelItineraryMetadataStore.RouteMetadata withoutRealtime(
            TravelItineraryMetadataStore.RouteMetadata metadata
    ) {
        return new TravelItineraryMetadataStore.RouteMetadata(
                metadata.lineName(),
                metadata.vehicleNumber(),
                metadata.nextArrivalMinutes(),
                metadata.estimatedDepartureAt(),
                metadata.estimatedArrivalAt(),
                false,
                metadata.lastRefreshedAt()
        );
    }

    private static void addQueryParam(UriComponentsBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(name, value);
        }
    }

    private static JsonNode unwrapResult(JsonNode response) {
        if (response == null || !response.isObject()) {
            return response;
        }
        JsonNode result = response.get("result");
        return result != null && result.isObject() ? result : response;
    }

    private static JsonNode parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(responseBody);
        } catch (Exception exception) {
            throw new IllegalStateException("대중교통 API 응답을 해석하지 못했습니다.", exception);
        }
    }

    private static java.util.Optional<String> text(JsonNode node, String... names) {
        if (node == null) {
            return java.util.Optional.empty();
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.isContainerNode()) {
                return java.util.Optional.of(value.asText());
            }
        }
        return java.util.Optional.empty();
    }

    private static Integer integer(JsonNode node, String... names) {
        return text(node, names).map(value -> {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).orElse(null);
    }

    private static LocalDateTime dateTime(JsonNode node, String... names) {
        return text(node, names).flatMap(value -> {
            try {
                return java.util.Optional.of(LocalDateTime.parse(value));
            } catch (RuntimeException ignored) {
                try {
                    return java.util.Optional.of(OffsetDateTime.parse(value).toLocalDateTime());
                } catch (RuntimeException ignoredAgain) {
                    return java.util.Optional.empty();
                }
            }
        }).orElse(null);
    }
}
