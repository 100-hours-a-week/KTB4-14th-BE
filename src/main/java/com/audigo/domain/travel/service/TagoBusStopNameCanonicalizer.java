package com.audigo.domain.travel.service;

import java.text.Normalizer;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TagoBusStopNameCanonicalizer {

    // TAGO의 후보 nodeId 목록과 순서가 동일하다고 확인한 표기만 도시에 맞춰 등록한다.
    private static final Map<String, Map<String, String>> VERIFIED_ALIASES = Map.of();

    private final Map<String, Map<String, String>> aliasesByCity;

    @Autowired
    public TagoBusStopNameCanonicalizer() {
        this(VERIFIED_ALIASES);
    }

    TagoBusStopNameCanonicalizer(Map<String, Map<String, String>> aliasesByCity) {
        this.aliasesByCity = aliasesByCity;
    }

    public String canonicalize(String cityCode, String stopName) {
        String normalizedName = normalize(stopName);
        String normalizedCityCode = cityCode == null ? "" : cityCode.trim();
        String representative = aliasesByCity.getOrDefault(normalizedCityCode, Map.of()).get(normalizedName);
        return representative == null ? normalizedName : normalize(representative);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
    }
}
