package com.audigo.domain.travel.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.audigo.domain.travel.entity.BudgetType;
import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.entity.TravelTransportType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TravelPlanRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidTravelPeriodAndPreferenceRange() {
        LocalDateTime arrival = LocalDateTime.now().plusDays(2);
        TravelPlanRequest request = new TravelPlanRequest(
                1L,
                arrival,
                arrival.minusHours(1),
                2,
                CompanionType.COUPLE,
                new TravelPreferenceRequest(
                        TravelPaceType.BALANCED,
                        TravelTransportType.PUBLIC_TRANSPORT,
                        1_000_000,
                        100_000,
                        BudgetType.KRW,
                        101,
                        List.of(
                                TravelThemeType.NATURE,
                                TravelThemeType.FOOD,
                                TravelThemeType.CULTURE,
                                TravelThemeType.REST
                        ),
                        List.of(FoodType.KOREAN),
                        ""
                )
        );

        Set<ConstraintViolation<TravelPlanRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "travelPeriodValid",
                        "preference.budgetRangeValid",
                        "preference.distancePreference",
                        "preference.themes"
                );
    }

    @Test
    void rejectsHeadcountThatDoesNotMatchCompanionType() {
        TravelPlanRequest request = new TravelPlanRequest(
                1L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2),
                1,
                CompanionType.FRIEND,
                validPreference()
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("headcountCompatibleWithCompanion");
    }

    @Test
    void rejectsMissingThemesButAllowsAnEmptyThemeList() {
        TravelPreferenceRequest missingThemes = new TravelPreferenceRequest(
                TravelPaceType.RELAXED,
                TravelTransportType.WALK,
                100_000,
                1_000_000,
                BudgetType.KRW,
                50,
                null,
                List.of(FoodType.KOREAN),
                ""
        );
        TravelPreferenceRequest emptyThemes = new TravelPreferenceRequest(
                TravelPaceType.RELAXED,
                TravelTransportType.WALK,
                100_000,
                1_000_000,
                BudgetType.KRW,
                50,
                List.of(),
                List.of(FoodType.KOREAN),
                ""
        );

        assertThat(validator.validate(missingThemes))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("themes");
        assertThat(validator.validate(emptyThemes)).isEmpty();
    }

    @Test
    void allowsEqualMinimumAndMaximumBudget() {
        TravelPreferenceRequest preference = new TravelPreferenceRequest(
                TravelPaceType.RELAXED,
                TravelTransportType.WALK,
                500_000,
                500_000,
                BudgetType.KRW,
                50,
                List.of(),
                List.of(),
                ""
        );

        assertThat(validator.validate(preference)).isEmpty();
    }

    private TravelPreferenceRequest validPreference() {
        return new TravelPreferenceRequest(
                TravelPaceType.RELAXED,
                TravelTransportType.WALK,
                100_000,
                1_000_000,
                BudgetType.KRW,
                50,
                List.of(TravelThemeType.REST),
                List.of(FoodType.KOREAN),
                ""
        );
    }
}
