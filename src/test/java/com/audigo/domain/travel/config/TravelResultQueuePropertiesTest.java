package com.audigo.domain.travel.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TravelResultQueuePropertiesTest {

    @Test
    void SQS_Long_Polling은_20초를_넘을_수_없다() {
        assertThatThrownBy(() -> new TravelResultQueueProperties(21, 5, 2, 20, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Long Polling");
    }

    @Test
    void 한_번에_받는_메시지는_10개를_넘을_수_없다() {
        assertThatThrownBy(() -> new TravelResultQueueProperties(20, 11, 2, 20, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("배치 크기");
    }

    @Test
    void 최대_수신_횟수는_1회_이상이어야_한다() {
        assertThatThrownBy(() -> new TravelResultQueueProperties(20, 5, 2, 20, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 수신 횟수");
    }
}
