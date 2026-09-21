package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.ItineraryItemType;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.PlaceType;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.TravelPlaceSource;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.PlaceRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanPlaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 결과의 날짜·장소·경로를 여행 도메인 테이블로 변환한다. */
@Service
public class TravelItineraryPersistenceService {

    private final ObjectMapper objectMapper;
    private final TravelGenerationProgressStore progressStore;
    private final ItineraryDayRepository dayRepository;
    private final ItineraryItemRepository itemRepository;
    private final RouteSegmentRepository routeRepository;
    private final TravelPlanPlaceRepository planPlaceRepository;
    private final PlaceRepository placeRepository;
    private final TravelItineraryMetadataStore metadataStore;

    public TravelItineraryPersistenceService(
            ObjectMapper objectMapper,
            TravelGenerationProgressStore progressStore,
            ItineraryDayRepository dayRepository,
            ItineraryItemRepository itemRepository,
            RouteSegmentRepository routeRepository,
            TravelPlanPlaceRepository planPlaceRepository,
            PlaceRepository placeRepository,
            TravelItineraryMetadataStore metadataStore
    ) {
        this.objectMapper = objectMapper;
        this.progressStore = progressStore;
        this.dayRepository = dayRepository;
        this.itemRepository = itemRepository;
        this.routeRepository = routeRepository;
        this.planPlaceRepository = planPlaceRepository;
        this.placeRepository = placeRepository;
        this.metadataStore = metadataStore;
    }

    /**
     * SSE 단계 payload 중 문서의 result.days[].stops[] 형태를 찾았을 때만 저장한다.
     * AI가 완료 이벤트만 보내는 동안에는 빈 일정을 만들지 않고 기존 흐름을 유지한다.
     */
    @Transactional
    public boolean persistIfPresent(TravelGenerationJob job) {
        JsonNode result = findResult(job.getId());
        JsonNode daysNode = result == null ? null : result.get("days");
        if (daysNode == null || !daysNode.isArray() || daysNode.isEmpty()) {
            return false;
        }

        TravelPlan plan = job.getTravelPlan();
        clearExisting(plan.getId());

        Map<String, TravelPlanPlace> planPlaces = new HashMap<>();
        List<TravelPlanPlace> existingPlanPlaces = planPlaceRepository
                .findAllByTravelPlanIdOrderByPlaceOrderAsc(plan.getId());
        existingPlanPlaces.forEach(value -> planPlaces.put(
                key(value.getPlace().getProvider(), value.getPlace().getProviderPlaceId()), value));
        Set<Long> usedPlanPlaceIds = new HashSet<>();

        Map<DaySequence, ItineraryItem> itemsBySequence = new HashMap<>();
        List<PendingRoute> pendingRoutes = new ArrayList<>();
        int dayIndex = 0;

        for (JsonNode dayNode : daysNode) {
            int dayNumber = positiveInt(first(dayNode, "day_number", "day", "dayNo"), dayIndex + 1);
            LocalDate date = parseDate(first(dayNode, "date", "travel_date"),
                    plan.getArrivalDatetime().toLocalDate().plusDays(dayNumber - 1L));
            if (date.isBefore(plan.getArrivalDatetime().toLocalDate())
                    || date.isAfter(plan.getDepartureDatetime().toLocalDate())) {
                throw new IllegalArgumentException("AI 일정 날짜가 여행 기간을 벗어났습니다.");
            }
            ItineraryDay day = dayRepository.save(ItineraryDay.create(plan, dayNumber, date));

            JsonNode stopsNode = first(dayNode, "stops", "items", "places");
            if (stopsNode == null || !stopsNode.isArray()) {
                dayIndex++;
                continue;
            }
            int fallbackSequence = 1;
            Set<Integer> sequences = new HashSet<>();
            for (JsonNode stop : stopsNode) {
                String providerPlaceId = text(stop, "provider_place_id", "providerPlaceId", "place_id", "placeId");
                if (providerPlaceId == null || providerPlaceId.isBlank()) {
                    continue;
                }
                PlaceProvider provider = parseProvider(text(stop, "provider"));
                PlaceType placeType = parsePlaceType(text(stop, "place_type", "placeType", "type"));
                TravelPlanPlace planPlace = planPlaces.get(key(provider, providerPlaceId));
                if (planPlace == null) {
                    Place place = placeRepository.findByProviderAndProviderPlaceId(provider, providerPlaceId)
                            .orElseGet(() -> placeRepository.save(Place.create(provider, providerPlaceId)));
                    int placeOrder = nextPlaceOrder(planPlaces.values());
                    planPlace = planPlaceRepository.save(TravelPlanPlace.create(
                            plan,
                            place,
                            placeType,
                            TravelPlaceSource.AI_RECOMMENDED,
                            placeOrder
                    ));
                    planPlaces.put(key(provider, providerPlaceId), planPlace);
                }
                usedPlanPlaceIds.add(planPlace.getId());

                int sequence = positiveInt(first(stop, "sequence", "order", "sort_order"), fallbackSequence);
                if (!sequences.add(sequence)) {
                    throw new IllegalArgumentException("AI 일정의 방문 순서가 중복되었습니다.");
                }
                LocalTime startTime = parseTime(first(stop, "start_time", "startTime"), LocalTime.of(9, 0));
                LocalTime endTime = parseTime(first(stop, "end_time", "endTime"), startTime.plusHours(1));
                if (!startTime.isBefore(endTime)) {
                    endTime = startTime.plusHours(1);
                }
                ItineraryItem item = itemRepository.save(ItineraryItem.create(
                        day,
                        planPlace,
                        sequence,
                        startTime,
                        endTime,
                        ItineraryItemType.PLACE
                ));
                itemsBySequence.put(new DaySequence(dayNumber, sequence), item);
                metadataStore.putPlace(item.getId(), new TravelItineraryMetadataStore.PlaceMetadata(
                        text(stop, "place_name", "placeName", "name"),
                        text(stop, "address", "road_address", "roadAddress"),
                        decimal(stop, "latitude", "lat"),
                        decimal(stop, "longitude", "lng", "lon")
                ));
                collectRoutes(dayNumber, stop, pendingRoutes);
                fallbackSequence++;
            }
            collectRoutes(dayNumber, dayNode, pendingRoutes);
            dayIndex++;
        }

        for (TravelPlanPlace required : plan.getRequiredPlaces()) {
            if (!usedPlanPlaceIds.contains(required.getId())) {
                throw new IllegalArgumentException("AI 일정에 필수 장소가 포함되지 않았습니다.");
            }
        }

        saveRoutes(plan, pendingRoutes, itemsBySequence, result);
        existingPlanPlaces.stream()
                .filter(value -> value.getSource() == TravelPlaceSource.AI_RECOMMENDED)
                .filter(value -> !usedPlanPlaceIds.contains(value.getId()))
                .forEach(planPlaceRepository::delete);
        return true;
    }

    private JsonNode findResult(Long jobId) {
        ObjectNode merged = null;
        for (TravelGenerationStage stage : List.of(
                TravelGenerationStage.ROUTE_OPTIMIZE,
                TravelGenerationStage.STAY_RECOMMEND,
                TravelGenerationStage.PLACE_RECOMMEND
        )) {
            JsonNode candidate = parse(progressStore.payload(jobId, stage));
            if (candidate == null) {
                continue;
            }
            JsonNode result = candidate.get("result");
            if (result != null && result.isObject()) {
                candidate = result;
            }
            if (!candidate.isObject()) {
                continue;
            }
            if (merged == null) {
                merged = candidate.deepCopy();
            } else {
                mergeDays(merged, candidate);
                copyIfPresent(merged, candidate, "route_segments");
                copyIfPresent(merged, candidate, "routes");
            }
        }
        return merged;
    }

    private void mergeDays(ObjectNode target, JsonNode source) {
        JsonNode sourceDays = source.get("days");
        if (sourceDays == null || !sourceDays.isArray()) {
            return;
        }
        JsonNode targetDays = target.get("days");
        if (targetDays == null || !targetDays.isArray()) {
            target.set("days", sourceDays.deepCopy());
            return;
        }
        Map<Integer, ObjectNode> byDay = new HashMap<>();
        for (JsonNode day : targetDays) {
            if (day instanceof ObjectNode objectDay) {
                byDay.put(positiveInt(first(day, "day_number", "day", "dayNo"), 1), objectDay);
            }
        }
        for (JsonNode day : sourceDays) {
            if (!(day instanceof ObjectNode sourceDay)) {
                continue;
            }
            ObjectNode targetDay = byDay.get(positiveInt(first(day, "day_number", "day", "dayNo"), 1));
            if (targetDay == null) {
                ((ArrayNode) targetDays).add(sourceDay.deepCopy());
                continue;
            }
            JsonNode sourceStops = first(sourceDay, "stops", "items", "places");
            if (sourceStops != null && sourceStops.isArray()) {
                JsonNode targetStops = first(targetDay, "stops", "items", "places");
                if (targetStops == null || !targetStops.isArray()) {
                    targetDay.set("stops", sourceStops.deepCopy());
                } else {
                    Set<String> ids = new HashSet<>();
                    targetStops.forEach(stop -> {
                        String id = text(stop, "provider_place_id", "providerPlaceId", "place_id", "placeId");
                        if (id != null) ids.add(id);
                    });
                    sourceStops.forEach(stop -> {
                        String id = text(stop, "provider_place_id", "providerPlaceId", "place_id", "placeId");
                        if (id == null || ids.add(id)) {
                            ((ArrayNode) targetStops).add(stop.deepCopy());
                        }
                    });
                }
            }
        }
    }

    private void copyIfPresent(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private void clearExisting(Long travelPlanId) {
        List<RouteSegment> oldRoutes = routeRepository.findAllByTravelPlanId(travelPlanId);
        oldRoutes = oldRoutes.stream()
                .sorted(Comparator.comparingInt(RouteSegment::getOrder))
                .toList();
        List<Long> routeIds = oldRoutes.stream().map(RouteSegment::getId).filter(Objects::nonNull).toList();
        routeRepository.deleteAll(oldRoutes);

        List<ItineraryDay> oldDays = dayRepository.findAllByTravelPlanIdOrderByDayNumberAsc(travelPlanId);
        List<Long> itemIds = oldDays.stream()
                .flatMap(day -> itemRepository.findAllByItineraryDayIdOrderBySequenceAsc(day.getId()).stream())
                .map(ItineraryItem::getId)
                .filter(Objects::nonNull)
                .toList();
        oldDays.forEach(day -> itemRepository.deleteAll(
                itemRepository.findAllByItineraryDayIdOrderBySequenceAsc(day.getId())));
        dayRepository.deleteAll(oldDays);
        metadataStore.removeForPlan(itemIds, routeIds);
    }

    private void saveRoutes(
            TravelPlan plan,
            List<PendingRoute> pendingRoutes,
            Map<DaySequence, ItineraryItem> itemsBySequence,
            JsonNode result
    ) {
        List<PendingRoute> allRoutes = new ArrayList<>(pendingRoutes);
        JsonNode rootRoutes = first(result, "route_segments", "routes");
        if (rootRoutes != null && rootRoutes.isArray()) {
            for (JsonNode route : rootRoutes) {
                allRoutes.add(PendingRoute.fromJson(0, route));
            }
        }
        Set<String> saved = new HashSet<>();
        allRoutes.sort(Comparator.comparingInt(PendingRoute::order));
        for (PendingRoute pending : allRoutes) {
            ItineraryItem from = resolveItem(pending, true, itemsBySequence);
            ItineraryItem to = resolveItem(pending, false, itemsBySequence);
            if (from == null || to == null || from.getId().equals(to.getId())) {
                continue;
            }
            String unique = from.getId() + ":" + to.getId();
            if (!saved.add(unique)) {
                continue;
            }
            RouteSegment route = routeRepository.save(RouteSegment.create(
                    plan,
                    from,
                    to,
                    pending.transportType(),
                    pending.durationMinutes(),
                    pending.distanceMeter(),
                    pending.cost(),
                    Math.max(1, pending.order())
            ));
            metadataStore.putRoute(route.getId(), pending.metadata());
        }
    }

    private ItineraryItem resolveItem(PendingRoute pending, boolean from, Map<DaySequence, ItineraryItem> items) {
        Long id = from ? pending.fromItemId() : pending.toItemId();
        if (id != null) {
            return items.values().stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }
        int sequence = from ? pending.fromSequence() : pending.toSequence();
        if (sequence < 1) {
            return null;
        }
        int day = pending.dayNumber() > 0 ? pending.dayNumber() : 1;
        return items.get(new DaySequence(day, sequence));
    }

    private void collectRoutes(int dayNumber, JsonNode node, List<PendingRoute> routes) {
        JsonNode routeNode = first(node, "route_segments", "routes");
        if (routeNode == null || !routeNode.isArray()) {
            return;
        }
        for (JsonNode route : routeNode) {
            routes.add(PendingRoute.fromJson(dayNumber, route));
        }
    }

    private int nextPlaceOrder(Iterable<TravelPlanPlace> values) {
        int max = 0;
        for (TravelPlanPlace value : values) {
            max = Math.max(max, value.getPlaceOrder());
        }
        return max + 1;
    }

    private JsonNode parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonNode first(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        return value == null || value.isContainerNode() ? null : value.asText(null);
    }

    private static int positiveInt(JsonNode value, int fallback) {
        if (value == null || !value.canConvertToInt() || value.asInt() < 1) {
            return fallback;
        }
        return value.asInt();
    }

    private static Integer integer(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null || !value.isNumber()) {
            try {
                return value == null ? null : Integer.valueOf(value.asText());
            } catch (Exception ignored) {
                return null;
            }
        }
        return value.intValue();
    }

    private static BigDecimal decimal(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null || value.isContainerNode()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(JsonNode value, LocalDate fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.asText());
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static LocalTime parseTime(JsonNode value, LocalTime fallback) {
        if (value == null) {
            return fallback;
        }
        String raw = value.asText();
        try {
            return LocalTime.parse(raw.length() == 5 ? raw + ":00" : raw);
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static PlaceProvider parseProvider(String value) {
        if (value == null) {
            return PlaceProvider.KAKAO;
        }
        try {
            return PlaceProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlaceProvider.KAKAO;
        }
    }

    private static PlaceType parsePlaceType(String value) {
        if (value == null) {
            return PlaceType.TOURISM;
        }
        try {
            return PlaceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlaceType.TOURISM;
        }
    }

    private static TravelTransportType parseTransport(String value) {
        if (value == null) {
            return TravelTransportType.CAR;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("WALK")) {
            return TravelTransportType.WALK;
        }
        if (normalized.contains("PUBLIC") || normalized.contains("TRANSIT")
                || normalized.contains("BUS") || normalized.contains("SUBWAY")
                || normalized.contains("METRO")) {
            return TravelTransportType.PUBLIC_TRANSPORT;
        }
        return TravelTransportType.CAR;
    }

    private static String key(PlaceProvider provider, String providerPlaceId) {
        return provider.name() + ":" + providerPlaceId;
    }

    private record DaySequence(int dayNumber, int sequence) {
    }

    private record PendingRoute(
            int dayNumber,
            Long fromItemId,
            Long toItemId,
            int fromSequence,
            int toSequence,
            TravelTransportType transportType,
            Integer durationMinutes,
            Integer distanceMeter,
            Integer cost,
            int order,
            TravelItineraryMetadataStore.RouteMetadata metadata
    ) {
        private static PendingRoute fromJson(int dayNumber, JsonNode node) {
            int resolvedDay = positiveInt(first(node, "day_number", "day"), dayNumber);
            Long fromItemId = longValue(first(node, "from_itinerary_item_id", "fromItemId"));
            Long toItemId = longValue(first(node, "to_itinerary_item_id", "toItemId"));
            int fromSequence = positiveInt(first(node, "from_sequence", "fromSequence", "from_order"), -1);
            int toSequence = positiveInt(first(node, "to_sequence", "toSequence", "to_order"), -1);
            Integer duration = integer(node, "duration_minutes", "durationMinutes");
            Integer distance = integer(node, "distance_meter", "distanceMeter");
            if (distance == null) {
                Integer km = integer(node, "distance_km", "distanceKm");
                distance = km == null ? null : km * 1000;
            }
            Integer cost = integer(node, "cost", "fare", "fare_amount");
            int order = positiveInt(first(node, "sort_order", "order", "sequence"), 1);
            TravelItineraryMetadataStore.RouteMetadata metadata = new TravelItineraryMetadataStore.RouteMetadata(
                    text(node, "line_name", "lineName", "subway_line", "bus_number"),
                    text(node, "vehicle_number", "vehicleNumber", "bus_number"),
                    integer(node, "next_arrival_minutes", "nextArrivalMinutes"),
                    parseDateTime(first(node, "estimated_departure_at", "estimatedDepartureAt")),
                    parseDateTime(first(node, "estimated_arrival_at", "estimatedArrivalAt")),
                    false,
                    null
            );
            return new PendingRoute(
                    resolvedDay,
                    fromItemId,
                    toItemId,
                    fromSequence,
                    toSequence,
                    parseTransport(text(node, "transport_type", "transportType", "mode")),
                    duration,
                    distance,
                    cost,
                    order,
                    metadata
            );
        }

        private static Long longValue(JsonNode value) {
            if (value == null) {
                return null;
            }
            try {
                return value.isNumber() ? value.longValue() : Long.valueOf(value.asText());
            } catch (Exception ignored) {
                return null;
            }
        }

        private static LocalDateTime parseDateTime(JsonNode value) {
            if (value == null) {
                return null;
            }
            String raw = value.asText();
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(raw).toLocalDateTime();
                } catch (DateTimeParseException ignoredAgain) {
                    return null;
                }
            }
        }
    }
}
