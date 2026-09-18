package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.BudgetType;
import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.RegionRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelPlanServiceTest {

    @Mock
    private TravelPlanRepository travelPlanRepository;

    @Mock
    private RegionRepository regionRepository;

    @InjectMocks
    private TravelPlanService travelPlanService;

    private Region region;

    @BeforeEach
    void setUp() {
        region = Region.create("제주", "제주특별자치도 제주시");
    }

    @Test
    void createsTravelPlanWithPreference() {
        when(regionRepository.findById(10L)).thenReturn(Optional.of(region));
        when(travelPlanRepository.save(any(TravelPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TravelPlan result = travelPlanService.createTravelPlan(1L, validRequest());

        assertThat(result.getPreference().getPaceType()).isEqualTo(TravelPaceType.BALANCED);
        assertThat(result.getPreference().getTransportType()).isEqualTo(TravelTransportType.CAR);
        assertThat(result.getPreference().getBudgetMin()).isEqualTo(100_000);
        assertThat(result.getPreference().getBudgetMax()).isEqualTo(1_000_000);
        assertThat(result.getPreference().getThemes())
                .extracting(theme -> theme.getTheme())
                .containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(result.getPreference().getFoods())
                .extracting(food -> food.getFoodType())
                .containsExactly(FoodType.KOREAN);
        verify(travelPlanRepository).save(any(TravelPlan.class));
    }

    @Test
    void rejectsDuplicatedPreferenceValuesBeforeCreatingPlan() {
        TravelPlanRequest request = new TravelPlanRequest(
                10L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(3),
                2,
                CompanionType.COUPLE,
                new TravelPreferenceRequest(
                        TravelPaceType.BALANCED,
                        TravelTransportType.CAR,
                        100_000,
                        1_000_000,
                        BudgetType.KRW,
                        50,
                        List.of(TravelThemeType.NATURE, TravelThemeType.NATURE),
                        List.of(FoodType.KOREAN),
                        "해안 도로를 포함해 주세요"
                )
        );

        assertThatThrownBy(() -> travelPlanService.createTravelPlan(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(travelPlanRepository, never()).save(any());
    }

    private TravelPlanRequest validRequest() {
        return new TravelPlanRequest(
                10L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(3),
                2,
                CompanionType.COUPLE,
                validPreference()
        );
    }

    private TravelPreferenceRequest validPreference() {
        return new TravelPreferenceRequest(
                TravelPaceType.BALANCED,
                TravelTransportType.CAR,
                100_000,
                1_000_000,
                BudgetType.KRW,
                50,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD),
                List.of(FoodType.KOREAN),
                "해안 도로를 포함해 주세요"
        );
    }

}
