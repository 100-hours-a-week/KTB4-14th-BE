package com.audigo.domain.travel.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.audigo.domain.travel.entity.BudgetType;
import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.entity.TravelTransportType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelPlanRequestTest {

    @Test
    void accepts_a_travel_period_of_exactly_seven_calendar_days() {
        LocalDateTime arrival = LocalDateTime.of(2026, 9, 1, 10, 0);
        TravelPlanRequest request = requestWithDates(arrival, LocalDateTime.of(2026, 9, 7, 18, 0));

        assertThat(request.isTravelDurationValid()).isTrue();
    }

    @Test
    void rejects_a_travel_period_longer_than_seven_calendar_days() {
        LocalDateTime arrival = LocalDateTime.of(2026, 9, 1, 10, 0);
        TravelPlanRequest request = requestWithDates(arrival, LocalDateTime.of(2026, 9, 8, 18, 0));

        assertThat(request.isTravelDurationValid()).isFalse();
    }

    private TravelPlanRequest requestWithDates(LocalDateTime arrival, LocalDateTime departure) {
        TravelPreferenceRequest preference = new TravelPreferenceRequest(
                TravelPaceType.BALANCED,
                TravelTransportType.WALK,
                100_000,
                1_000_000,
                BudgetType.KRW,
                50,
                List.<TravelThemeType>of(),
                List.<FoodType>of(),
                ""
        );
        return new TravelPlanRequest(
                1L,
                arrival,
                departure,
                2,
                CompanionType.FRIEND,
                preference,
                List.of()
        );
    }
}
