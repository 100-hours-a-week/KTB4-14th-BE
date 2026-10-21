package com.audigo.domain.travel.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TravelSqsPropertiesTest {

    @Test
    void 비활성화하면_큐_URL_없이도_기존_애플리케이션을_실행할_수_있다() {
        TravelSqsProperties properties = new TravelSqsProperties(
                false,
                "ap-northeast-2",
                null,
                null,
                null
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.jobQueueUrl()).isEmpty();
        assertThat(properties.resultQueueUrl()).isEmpty();
    }

    @Test
    void 활성화하면_리전과_두_큐_URL이_모두_필요하다() {
        assertThatThrownBy(() -> new TravelSqsProperties(
                true,
                "ap-northeast-2",
                "https://sqs.ap-northeast-2.amazonaws.com/123/job",
                "",
                ""
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI Result Queue URL");
    }
}
