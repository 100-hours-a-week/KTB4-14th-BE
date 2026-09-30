package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 일정의 지역명과 BUS leg의 탑승 정류장명으로 TAGO 조회 후보를 찾는다.
 * 후보가 여러 개일 수 있으므로 여기서는 하나를 확정하지 않고 모두 반환한다.
 */
@Component
public class TagoBusStopResolver {

    private final TagoBusStopClient tagoBusStopClient;

    public TagoBusStopResolver(TagoBusStopClient tagoBusStopClient) {
        this.tagoBusStopClient = tagoBusStopClient;
    }

    public List<TagoStopIdentifier> resolveCandidates(RouteSegment route, RouteSegmentLeg leg) {
        if (route == null || leg == null || !isBusMode(leg.getMode())) {
            return List.of();
        }
        if (route.getTravelPlan() == null || route.getTravelPlan().getRegion() == null) {
            return List.of();
        }
        Region region = route.getTravelPlan().getRegion();
        if (isBlank(region.getFullName()) || isBlank(leg.getBoardingStopName())) {
            return List.of();
        }

        List<TagoBusStopClient.City> cities = tagoBusStopClient.findCities();
        Optional<String> cityCode = resolveCityCode(cities, region.getFullName());
        if (cityCode.isEmpty()) {
            return List.of();
        }

        String stopName = leg.getBoardingStopName().trim();
        List<TagoBusStopClient.Stop> rawStops = tagoBusStopClient.findStopsByName(cityCode.get(), stopName);
        List<TagoBusStopClient.Stop> candidates = rawStops.stream()
                .filter(stop -> isBlank(stop.cityCode()) || cityCode.get().equals(stop.cityCode().trim()))
                .filter(stop -> normalize(stop.nodeName()).equals(normalize(stopName)))
                .filter(stop -> !isBlank(stop.nodeId()))
                .toList();
        return candidates.stream()
                .map(TagoBusStopClient.Stop::nodeId)
                .map(String::trim)
                .distinct()
                .map(nodeId -> new TagoStopIdentifier(cityCode.get(), nodeId))
                .toList();
    }

    private Optional<String> resolveCityCode(List<TagoBusStopClient.City> cities, String regionName) {
        String normalizedRegionCity = normalizeAdministrativeName(firstRegionToken(regionName));
        List<String> cityCodes = cities.stream()
                .filter(city -> !isBlank(city.cityCode()) && !isBlank(city.cityName()))
                .filter(city -> matchesRegionCity(city.cityName(), normalizedRegionCity))
                .map(TagoBusStopClient.City::cityCode)
                .map(String::trim)
                .distinct()
                .toList();
        return cityCodes.size() == 1 ? Optional.of(cityCodes.get(0)) : Optional.empty();
    }

    private static boolean matchesRegionCity(String cityName, String normalizedRegionCity) {
        return Arrays.stream(cityName.split("/"))
                .map(TagoBusStopResolver::normalizeAdministrativeName)
                .anyMatch(normalizedRegionCity::equals);
    }

    private static String firstRegionToken(String regionName) {
        String trimmed = regionName == null ? "" : regionName.trim();
        int firstSpace = trimmed.indexOf(' ');
        return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    }

    private static String normalizeAdministrativeName(String value) {
        return normalize(value)
                .replaceFirst(
                        "(특별자치시|특별자치도|특별시|광역시|자치시|자치도|시|도|군)$",
                        ""
                );
    }

    private static boolean isBusMode(String mode) {
        if (isBlank(mode)) {
            return false;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return "BUS".equals(normalized)
                || "EXPRESSBUS".equals(normalized)
                || "INTERCITY_BUS".equals(normalized);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record TagoStopIdentifier(String cityCode, String nodeId) {
    }
}
