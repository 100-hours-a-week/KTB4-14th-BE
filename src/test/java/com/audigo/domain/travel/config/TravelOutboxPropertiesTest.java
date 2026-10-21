package com.audigo.domain.travel.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TravelOutboxPropertiesTest {

    @Test
    void 최대_재시도_간격은_기본_간격보다_길어야_한다() {
        assertThatThrownBy(() -> new TravelOutboxProperties(10, 30, 5, 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 재시도 간격");
    }
}
