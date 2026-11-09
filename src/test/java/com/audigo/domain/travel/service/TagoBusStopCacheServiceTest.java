package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.config.TagoBusStopCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TagoBusStopCacheServiceTest {

    private TagoBusStopClient tagoClient;
    private ValueOperations<String, String> values;
    private TagoBusStopCacheService cache;

    @BeforeEach
    void setUp() {
        tagoClient = mock(TagoBusStopClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new TagoBusStopCacheService(
                tagoClient,
                new TagoBusStopNameCanonicalizer(Map.of("31010", Map.of("수원 역", "수원역"))),
                redis,
                new ObjectMapper(),
                new TagoBusStopCacheProperties(true, Duration.ofHours(24), Duration.ofHours(6),
                        Duration.ofMinutes(5), 0.1),
                new SimpleMeterRegistry());
    }

    @Test
    void 대표_검색어의_정류장_ID만_6시간_주변_TTL로_저장한다() {
        when(tagoClient.findStopsByName("31010", "수원역")).thenReturn(List.of(
                new TagoBusStopClient.Stop("31010", "SWA000001", "수원역", "1234")));

        assertThat(cache.findNodeIdsByName("31010", "수원   역")).containsExactly("SWA000001");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(anyString(), eq("[\"SWA000001\"]"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(324), Duration.ofMinutes(396));
        verify(tagoClient).findStopsByName("31010", "수원역");
    }

    @Test
    void 정상적인_빈_정류장_결과는_5분_고정으로_저장한다() {
        when(tagoClient.findStopsByName("31010", "없는정류장")).thenReturn(List.of());

        assertThat(cache.findNodeIdsByName("31010", "없는정류장")).isEmpty();

        verify(values).set(anyString(), eq("[]"), eq(Duration.ofMinutes(5)));
    }

    @Test
    void 캐시_적중시_TAGO를_호출하지_않는다() {
        when(values.get(anyString())).thenReturn("[\"SWA000001\"]");

        assertThat(cache.findNodeIdsByName("31010", "수원 역")).containsExactly("SWA000001");

        verify(tagoClient, never()).findStopsByName(anyString(), anyString());
    }
}
