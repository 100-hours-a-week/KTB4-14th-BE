package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

/**
 * 일정의 지역명과 BUS leg의 탑승 정류장명을 TAGO 정류장 조회 요청으로 변환한다.
 * TAGO가 반환한 정류장 후보를 임의로 하나로 확정하거나 정류장명을 재검증하지 않는다.
 */
@Component
public class TagoBusStopLookupService {

    private final TagoBusStopClient tagoBusStopClient;

    public TagoBusStopLookupService(TagoBusStopClient tagoBusStopClient) {
        this.tagoBusStopClient = tagoBusStopClient;
    }

    public List<TagoStopIdentifier> findCandidates(RouteSegment route, RouteSegmentLeg leg) {
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

        String regionCityName = normalizeAdministrativeName(firstRegionToken(region.getFullName()));
        List<String> cityCodes = tagoBusStopClient.findCities().stream()
                .filter(city -> !isBlank(city.cityCode()) && !isBlank(city.cityName()))
                .filter(city -> matchesRegionCity(city.cityName(), regionCityName))
                .map(TagoBusStopClient.City::cityCode)
                .map(String::trim)
                .distinct()
                .toList();

        Set<TagoStopIdentifier> candidates = new LinkedHashSet<>();
        String stopName = leg.getBoardingStopName().trim();
        for (String cityCode : cityCodes) {
            tagoBusStopClient.findStopsByName(cityCode, stopName).stream()
                    .map(TagoBusStopClient.Stop::nodeId)
                    .filter(nodeId -> !isBlank(nodeId))
                    .map(String::trim)
                    .map(nodeId -> new TagoStopIdentifier(cityCode, nodeId))
                    .forEach(candidates::add);
        }

        return candidates.stream().toList();
    }

    private static boolean matchesRegionCity(String cityName, String normalizedRegionCity) {
        return Arrays.stream(cityName.split("/"))
                .map(TagoBusStopLookupService::normalizeAdministrativeName)
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
