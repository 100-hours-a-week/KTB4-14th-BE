package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.BudgetType;
import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.PlaceRepository;
import com.audigo.domain.travel.repository.RegionRepository;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
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

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private TravelGenerationJobRepository travelGenerationJobRepository;

    @InjectMocks
    private TravelPlanService travelPlanService;

    @Test
    void rejects_new_travel_when_user_has_a_generating_job() {
        when(travelGenerationJobRepository.existsByTravelPlanUserIdAndStatus(
                1L,
                TravelPlanStatus.GENERATING
        )).thenReturn(true);

        assertThatThrownBy(() -> travelPlanService.createTravelPlan(1L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.TRAVEL_GENERATION_IN_PROGRESS)
                );

        verify(regionRepository, never()).findById(1L);
        verify(travelPlanRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private TravelPlanRequest validRequest() {
        LocalDateTime arrival = LocalDateTime.now().plusDays(1);
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
                arrival.plusDays(2),
                2,
                CompanionType.FRIEND,
                preference,
                List.of()
        );
    }
}
