package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.RouteSegment;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 대중교통 도착 정보 어댑터
 * 실제 제공자 계약이 확정되기 전에는 AI 예상값을 유지하고, URL을 설정하면 공통 필드로 보강 예정
 */
@Service
public class PublicTransportRealtimeService {

    private final RestClient restClient;
    private final TravelItineraryMetadataStore metadataStore;
    private final String baseUrl;

    public PublicTransportRealtimeService(
            RestClient.Builder restClientBuilder,
            TravelItineraryMetadataStore metadataStore,
            @Value("${audigo.transit.base-url:}") String baseUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.metadataStore = metadataStore;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public void refresh(RouteSegment route, LocalDateTime completedAt) {
        TravelItineraryMetadataStore.RouteMetadata current = metadataStore.route(route.getId());
        if (current == null) {
            current = new TravelItineraryMetadataStore.RouteMetadata(
                    null, null, null, null, null, false, null);
        }
        if (baseUrl.isBlank()) {
            metadataStore.putRoute(route.getId(), current);
            return;
        }
        final TravelItineraryMetadataStore.RouteMetadata currentMetadata = current;

        try {
            URI requestUri = UriComponentsBuilder.fromUri(URI.create(baseUrl))
                    .queryParam("line", currentMetadata.lineName())
                    .queryParam("vehicle", currentMetadata.vehicleNumber())
                    .queryParam("at", completedAt)
                    .build()
                    .toUri();
            JsonNode response = restClient.get()
                    .uri(requestUri)
                    .retrieve()
                    .body(JsonNode.class);
            Integer nextArrival = integer(response, "next_arrival_minutes", "nextArrivalMinutes", "arrival_minutes");
            LocalDateTime departure = dateTime(response, "estimated_departure_at", "estimatedDepartureAt");
            LocalDateTime arrival = dateTime(response, "estimated_arrival_at", "estimatedArrivalAt");
            metadataStore.putRoute(route.getId(), new TravelItineraryMetadataStore.RouteMetadata(
                    text(response, "line_name", "lineName").orElse(currentMetadata.lineName()),
                    text(response, "vehicle_number", "vehicleNumber", "bus_number").orElse(currentMetadata.vehicleNumber()),
                    nextArrival == null ? currentMetadata.nextArrivalMinutes() : nextArrival,
                    departure == null ? currentMetadata.estimatedDepartureAt() : departure,
                    arrival == null ? currentMetadata.estimatedArrivalAt() : arrival,
                    true,
                    LocalDateTime.now()
            ));
        } catch (RuntimeException ignored) {
            // 외부 교통 API 장애 시 AI가 계산한 1차 경로를 그대로 제공한다.
            metadataStore.putRoute(route.getId(), currentMetadata);
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
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }
}
