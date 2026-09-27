package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.ItineraryItemType;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.PlaceType;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
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

// AI 결과의 날짜·장소·경로를 여행 도메인 테이블로 변환
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

    // SSE 단계 payload의 일정·경로 결과를 저장한다.축약 결과에는 필수 장소 fallback을 적용하고, 일정과 연결할 수 없는 결과는 저장하지 않는다.
    @Transactional
    public boolean persistIfPresent(TravelGenerationJob job) {
        TravelPlan plan = job.getTravelPlan();
        JsonNode result = findResult(job.getId());
        JsonNode daysNode = result == null ? null : result.get("days");
        if (daysNode == null || !daysNode.isArray() || daysNode.isEmpty()) {
            daysNode = fallbackDaysFromRequiredPlaces(result, plan);
            if (daysNode == null) {
                return false;
            }
        }

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
                PlaceType placeType = parsePlaceType(text(stop, "category", "place_type", "placeType", "type"));
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

                int sequence = positiveInt(first(stop, "sequence", "order"), fallbackSequence);
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
                        text(stop, "road_address", "roadAddress", "address"),
                        decimal(stop, "latitude", "lat"),
                        decimal(stop, "longitude", "lng", "lon")
                ));
                collectRouteFromPrevious(dayNumber, stop, sequence, pendingRoutes);
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

    /**
     * 축약된 AI Mock처럼 route_segments만 전달된 경우에도 기존 필수 장소를 일정으로 연결한다.
     * 실제 AI가 days를 전달하면 그 결과를 우선 사용하며, 여러 날짜를 추정해야 하는 경우에는
     * 잘못된 일정을 만들지 않도록 저장을 거부한다.
     */
    private ArrayNode fallbackDaysFromRequiredPlaces(JsonNode result, TravelPlan plan) {
        JsonNode routeSegments = result == null ? null : first(result, "route_segments", "routes");
        if (routeSegments == null || !routeSegments.isArray() || routeSegments.isEmpty()
                || plan.getRequiredPlaces().isEmpty()) {
            return null;
        }
        for (JsonNode route : routeSegments) {
            if (positiveInt(first(route, "day_number", "day"), 1) != 1) {
                return null;
            }
        }

        ObjectNode day = objectMapper.createObjectNode();
        day.put("day_number", 1);
        day.put("date", plan.getArrivalDatetime().toLocalDate().toString());
        ArrayNode stops = day.putArray("stops");
        int sequence = 1;
        for (TravelPlanPlace requiredPlace : plan.getRequiredPlaces()) {
            ObjectNode stop = stops.addObject();
            stop.put("provider", requiredPlace.getPlace().getProvider().name());
            stop.put("provider_place_id", requiredPlace.getPlace().getProviderPlaceId());
            stop.put("sequence", sequence++);
            stop.put("category", requiredPlace.getPlaceType().name());
        }
        ArrayNode days = objectMapper.createArrayNode();
        days.add(day);
        return days;
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
            RouteSegment route = RouteSegment.create(
                    plan,
                    from,
                    to,
                    pending.transportType(),
                    pending.durationMinutes(),
                    pending.distanceMeter(),
                    pending.totalFareAmount(),
                    Math.max(1, pending.order())
            );
            for (PendingRouteLeg pendingLeg : pending.legs()) {
                route.addLeg(RouteSegmentLeg.create(
                        route,
                        pendingLeg.sequence(),
                        pendingLeg.mode(),
                        pendingLeg.busNumbers(),
                        pendingLeg.subwayLines(),
                        pendingLeg.boardingStopName(),
                        pendingLeg.boardingStationNumber(),
                        pendingLeg.alightingStopName(),
                        pendingLeg.alightingStationNumber(),
                        pendingLeg.durationMinute(),
                        pendingLeg.distanceMeter()
                ));
            }
            route = routeRepository.save(route);
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

    /**
     * 최신 AI 응답은 각 일정 항목 안에 이전 장소에서 현재 장소까지의 경로를
     * {@code route_from_previous}로 담는다. 기존 route_segments 형태와 동일한
     * 내부 모델로 변환해 저장한다.
     */
    private void collectRouteFromPrevious(
            int dayNumber,
            JsonNode item,
            int toSequence,
            List<PendingRoute> routes
    ) {
        JsonNode routeNode = first(item, "route_from_previous", "routeFromPrevious");
        if (routeNode == null || !routeNode.isObject() || toSequence <= 1) {
            return;
        }
        ObjectNode normalized = ((ObjectNode) routeNode).deepCopy();
        normalized.put("day_number", dayNumber);
        normalized.put("from_sequence", toSequence - 1);
        normalized.put("to_sequence", toSequence);
        normalized.put("order", toSequence - 1);
        routes.add(PendingRoute.fromJson(dayNumber, normalized));
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

    /**
     * AI가 현재 배열로 보내는 vehicle_number·line_name과
     * 기존 scalar 응답을 모두 읽는다. 객체나 null 값은 식별자로 사용하지 않는다.
     */
    private static List<String> texts(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    addText(values, item);
                }
            } else {
                addText(values, value);
            }
            if (!values.isEmpty()) {
                return List.copyOf(values);
            }
        }
        return List.of();
    }

    private static void addText(List<String> values, JsonNode value) {
        if (value == null || value.isContainerNode()) {
            return;
        }
        String text = value.asText(null);
        if (text != null && !text.isBlank() && !values.contains(text.trim())) {
            values.add(text.trim());
        }
    }

    private static String firstText(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
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
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RESTAURANT", "FOOD", "DINING", "음식", "음식점", "식당", "맛집" -> PlaceType.RESTAURANT;
            case "ACCOMMODATION", "LODGING", "STAY", "HOTEL", "숙소", "숙박", "호텔" -> PlaceType.ACCOMMODATION;
            case "TOUR", "TOURISM", "ATTRACTION", "관광", "관광지", "명소" -> PlaceType.TOURISM;
            default -> parseInternalPlaceType(normalized);
        };
    }

    private static PlaceType parseInternalPlaceType(String value) {
        try {
            return PlaceType.valueOf(value);
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

    private record PendingRouteLeg(
            int sequence,
            String mode,
            List<String> busNumbers,
            List<String> subwayLines,
            String boardingStopName,
            String boardingStationNumber,
            String alightingStopName,
            String alightingStationNumber,
            Integer durationMinute,
            Integer distanceMeter
    ) {
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
            Integer totalFareAmount,
            int order,
            List<PendingRouteLeg> legs,
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
            Integer totalFareAmount = integer(node, "total_fare_amount");
            int order = positiveInt(first(node, "order"), 1);
            List<PendingRouteLeg> legs = parseLegs(node);
            String lineName = firstText(texts(node, "subway_line", "line_name", "lineName", "line"));
            String vehicleNumber = firstText(texts(node, "bus_number", "vehicle_number", "vehicleNumber", "vehicle"));
            if (lineName == null) {
                lineName = legs.stream()
                        .flatMap(leg -> leg.subwayLines().stream())
                        .findFirst()
                        .orElse(null);
            }
            if (vehicleNumber == null) {
                vehicleNumber = legs.stream()
                        .flatMap(leg -> leg.busNumbers().stream())
                        .findFirst()
                        .orElse(null);
            }
            String transport = text(node, "transport_type", "transportType", "mode");
            if (transport == null) {
                transport = legs.stream().map(PendingRouteLeg::mode).findFirst().orElse(null);
            }
            TravelItineraryMetadataStore.RouteMetadata metadata = new TravelItineraryMetadataStore.RouteMetadata(
                    lineName,
                    vehicleNumber,
                    integer(node, "next_arrival_minutes", "nextArrivalMinutes", "arrival_minutes", "arrivalMinutes"),
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
                    parseTransport(transport),
                    duration,
                    distance,
                    totalFareAmount,
                    order,
                    legs,
                    metadata
            );
        }

        private static List<PendingRouteLeg> parseLegs(JsonNode node) {
            JsonNode values = first(node, "legs");
            if (values == null || !values.isArray()) {
                return List.of();
            }
            List<PendingRouteLeg> legs = new ArrayList<>();
            int fallbackSequence = 1;
            for (JsonNode leg : values) {
                String mode = text(leg, "mode");
                if (mode == null || mode.isBlank()) {
                    continue;
                }
                JsonNode boardingStop = first(leg, "boarding_stop", "boardingStop", "start");
                JsonNode alightingStop = first(leg, "alighting_stop", "alightingStop", "end");
                String normalizedMode = mode.trim().toUpperCase(Locale.ROOT);
                List<String> busNumbers = isBusMode(normalizedMode)
                        ? texts(leg, "bus_number", "vehicle_number", "vehicleNumber")
                        : List.of();
                List<String> subwayLines = isSubwayMode(normalizedMode)
                        ? texts(leg, "subway_line", "line_name", "lineName", "line")
                        : List.of();
                legs.add(new PendingRouteLeg(
                        positiveInt(first(leg, "sequence"), fallbackSequence),
                        normalizedMode,
                        busNumbers,
                        subwayLines,
                        text(boardingStop, "name", "stop_name", "station_name"),
                        text(boardingStop, "station_number", "stationNumber"),
                        text(alightingStop, "name", "stop_name", "station_name"),
                        text(alightingStop, "station_number", "stationNumber"),
                        integer(leg, "duration_minute", "duration_minutes", "durationMinute", "durationMinutes"),
                        integer(leg, "distance_meter", "distanceMeter")
                ));
                fallbackSequence++;
            }
            return List.copyOf(legs);
        }

        private static boolean isBusMode(String mode) {
            return "BUS".equals(mode) || "EXPRESSBUS".equals(mode) || "INTERCITY_BUS".equals(mode);
        }

        private static boolean isSubwayMode(String mode) {
            return "SUBWAY".equals(mode) || "METRO".equals(mode);
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
