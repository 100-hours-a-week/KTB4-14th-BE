package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.TagoBusStopCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TagoBusStopCacheService {

    private static final Logger log = LoggerFactory.getLogger(TagoBusStopCacheService.class);
    private static final String KEY_PREFIX = "transit:stop:v2:";
    private static final String CITY_KEY = KEY_PREFIX + "cities";
    private static final TypeReference<List<TagoBusStopClient.City>> CITY_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> NODE_IDS_TYPE = new TypeReference<>() { };

    private final TagoBusStopClient tagoClient;
    private final TagoBusStopNameCanonicalizer canonicalizer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TagoBusStopCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public TagoBusStopCacheService(
            TagoBusStopClient tagoClient,
            TagoBusStopNameCanonicalizer canonicalizer,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TagoBusStopCacheProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.tagoClient = tagoClient;
        this.canonicalizer = canonicalizer;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public List<TagoBusStopClient.City> findCities() {
        return getOrLoad("cities", CITY_KEY, CITY_TYPE, tagoClient::findCities,
                cities -> cities.isEmpty() ? Duration.ZERO : jitter(properties.cityTtl()));
    }

    public List<String> findNodeIdsByName(String cityCode, String stopName) {
        if (cityCode == null || cityCode.isBlank()) {
            return List.of();
        }
        String normalizedCityCode = cityCode.trim();
        String queryName = canonicalizer.canonicalize(normalizedCityCode, stopName);
        if (queryName.isBlank()) {
            return List.of();
        }
        String key = stopKey(normalizedCityCode, queryName);
        return getOrLoad("stops", key, NODE_IDS_TYPE,
                () -> tagoClient.findStopsByName(normalizedCityCode, queryName).stream()
                        .map(TagoBusStopClient.Stop::nodeId)
                        .filter(nodeId -> nodeId != null && !nodeId.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList(),
                nodeIds -> nodeIds.isEmpty() ? properties.emptyStopTtl() : jitter(properties.stopTtl()));
    }

    private <T> T getOrLoad(String cacheName, String key, TypeReference<T> type,
                            Supplier<T> loader, Function<T, Duration> ttlResolver) {
        if (!properties.enabled()) {
            return loader.get();
        }
        CacheRead<T> cached = read(cacheName, key, type);
        if (!cached.available()) {
            metric(cacheName, "bypass");
            metric(cacheName, "source_lookup");
            return loader.get();
        }
        if (cached.value().isPresent()) {
            metric(cacheName, "hit");
            return cached.value().get();
        }
        metric(cacheName, "miss");
        metric(cacheName, "source_lookup");
        T loaded = loader.get();
        Duration ttl = ttlResolver.apply(loaded);
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            write(cacheName, key, loaded, ttl);
        }
        return loaded;
    }

    private <T> CacheRead<T> read(String cacheName, String key, TypeReference<T> type) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return new CacheRead<>(value == null ? Optional.empty()
                    : Optional.ofNullable(objectMapper.readValue(value, type)), true);
        } catch (JsonProcessingException exception) {
            metric(cacheName, "deserialize_error");
            log.warn("TAGO 정류장 캐시 값 해석 실패. key={}", key, exception);
            return new CacheRead<>(Optional.empty(), true);
        } catch (DataAccessException exception) {
            metric(cacheName, "read_error");
            log.warn("TAGO 정류장 캐시 조회 실패. key={}", key, exception);
            return new CacheRead<>(Optional.empty(), false);
        }
    }

    private void write(String cacheName, String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            metric(cacheName, "write");
        } catch (JsonProcessingException | DataAccessException exception) {
            metric(cacheName, "write_error");
            log.warn("TAGO 정류장 캐시 저장 실패. key={}", key, exception);
        }
    }

    private Duration jitter(Duration baseTtl) {
        double ratio = properties.ttlJitterRatio();
        if (ratio == 0) {
            return baseTtl;
        }
        long baseMillis = baseTtl.toMillis();
        long range = Math.max(1, Math.round(baseMillis * ratio));
        long offset = ThreadLocalRandom.current().nextLong(-range, range + 1);
        return Duration.ofMillis(Math.max(1, baseMillis + offset));
    }

    private void metric(String cacheName, String result) {
        meterRegistry.counter("audigo.transit.stop.cache.operations", "cache", cacheName, "result", result)
                .increment();
    }

    private static String stopKey(String cityCode, String queryName) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(queryName.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + "stops:" + cityCode + ":" + encoded;
    }

    private record CacheRead<T>(Optional<T> value, boolean available) {
    }
}
