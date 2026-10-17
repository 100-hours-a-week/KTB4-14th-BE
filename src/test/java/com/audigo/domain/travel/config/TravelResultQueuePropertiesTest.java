package com.audigo.domain.travel.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TravelResultQueuePropertiesTest {

    @Test
    void SQS_Long_Polling은_20초를_넘을_수_없다() {
        assertThatThrownBy(() -> new TravelResultQueueProperties(21, 5, 2, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Long Polling");
    }

    @Test
    void 한_번에_받는_메시지는_10개를_넘을_수_없다() {
        assertThatThrownBy(() -> new TravelResultQueueProperties(20, 11, 2, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("배치 크기");
    }
}
