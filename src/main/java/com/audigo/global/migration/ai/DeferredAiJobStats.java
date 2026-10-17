package com.audigo.global.migration.ai;

import java.time.Duration;
import java.time.Instant;

// AI 시작 지연 상태. enabledAt은 지연을 켠 시각이며 꺼져 있으면 null
public record DeferredAiJobStats(boolean enabled, Instant enabledAt, int size, Duration oldestWait) {
}
