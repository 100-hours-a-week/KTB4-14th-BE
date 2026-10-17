package com.audigo.global.migration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 단계별 시각과 경과 시간을 같은 시계로 계산한다. 테스트에서는 고정 시계로 바꿀 수 있다
@Configuration(proxyBeanMethods = false)
public class MigrationClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
