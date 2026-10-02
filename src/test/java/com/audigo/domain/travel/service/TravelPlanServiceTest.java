package com.audigo.domain.travel.service;

import static org.mockito.ArgumentMatchers.any;
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
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelPlan;
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
import java.util.Optional;
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

    @Test
    void finds_upcoming_travel_from_confirmed_completed_plans() {
        TravelPlan travelPlan = travelPlan();
        travelPlan.markCompleted();
        travelPlan.confirm(LocalDateTime.now());
        when(travelPlanRepository
                .findTopByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeGreaterThanEqualOrderByArrivalDatetimeAsc(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(TravelPlanStatus.COMPLETED),
                        any(LocalDateTime.class)
                )).thenReturn(Optional.of(travelPlan));

        var response = travelPlanService.getUpcomingTravel(1L);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TravelPlanStatus.COMPLETED);
        assertThat(response.confirmedAt()).isEqualTo(travelPlan.getConfirmedAt());
    }

    @Test
    void finds_current_travel_from_confirmed_completed_plans_until_departure_date() {
        TravelPlan travelPlan = currentTravelPlan();
        travelPlan.markCompleted();
        travelPlan.confirm(LocalDateTime.now());
        when(travelPlanRepository
                .findTopByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeGreaterThanEqualOrderByArrivalDatetimeAsc(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(TravelPlanStatus.COMPLETED),
                        any(LocalDateTime.class)
                )).thenReturn(Optional.of(travelPlan));

        var response = travelPlanService.getUpcomingTravel(1L);

        assertThat(response).isNotNull();
        assertThat(response.travelPlanId()).isEqualTo(travelPlan.getId());
        assertThat(response.status()).isEqualTo(TravelPlanStatus.COMPLETED);
        assertThat(response.confirmedAt()).isEqualTo(travelPlan.getConfirmedAt());
    }

    @Test
    void finds_recent_travels_from_confirmed_completed_plans() {
        TravelPlan travelPlan = pastTravelPlan();
        travelPlan.markCompleted();
        travelPlan.confirm(LocalDateTime.now());
        when(travelPlanRepository
                .findTop5ByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeLessThanOrderByDepartureDatetimeDesc(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(TravelPlanStatus.COMPLETED),
                        any(LocalDateTime.class)
                )).thenReturn(List.of(travelPlan));

        var responses = travelPlanService.getRecentTravels(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo(TravelPlanStatus.COMPLETED);
        assertThat(responses.get(0).confirmedAt()).isEqualTo(travelPlan.getConfirmedAt());
    }

    @Test
    void confirms_completed_travel_plan() {
        TravelPlan travelPlan = travelPlan();
        travelPlan.markCompleted();
        when(travelPlanRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(travelPlan));

        var response = travelPlanService.confirmTravel(1L, 10L);

        assertThat(travelPlan.isConfirmed()).isTrue();
        assertThat(travelPlan.getConfirmedAt()).isNotNull();
        assertThat(response.confirmedAt()).isEqualTo(travelPlan.getConfirmedAt());
    }

    @Test
    void rejects_confirming_travel_before_generation_completed() {
        TravelPlan travelPlan = travelPlan();
        when(travelPlanRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(travelPlan));

        assertThatThrownBy(() -> travelPlanService.confirmTravel(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );

        assertThat(travelPlan.isConfirmed()).isFalse();
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

    private TravelPlan travelPlan() {
        LocalDateTime arrival = LocalDateTime.now().plusDays(1);
        return TravelPlan.create(
                1L,
                Region.create("강남구", "서울특별시 강남구"),
                arrival,
                arrival.plusDays(2),
                2,
                CompanionType.FRIEND
        );
    }

    private TravelPlan pastTravelPlan() {
        LocalDateTime arrival = LocalDateTime.now().minusDays(3);
        return TravelPlan.create(
                1L,
                Region.create("강남구", "서울특별시 강남구"),
                arrival,
                arrival.plusDays(2),
                2,
                CompanionType.FRIEND
        );
    }

    private TravelPlan currentTravelPlan() {
        LocalDateTime arrival = LocalDateTime.now().minusDays(1);
        return TravelPlan.create(
                1L,
                Region.create("강남구", "서울특별시 강남구"),
                arrival,
                arrival.plusDays(2),
                2,
                CompanionType.FRIEND
        );
    }
}
