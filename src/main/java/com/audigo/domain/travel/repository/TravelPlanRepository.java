package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPlanRepository extends JpaRepository<TravelPlan, Long> {

    Optional<TravelPlan> findByIdAndUserId(Long id, Long userId);

    Optional<TravelPlan> findTopByUserIdAndStatusAndDepartureDatetimeGreaterThanEqualOrderByArrivalDatetimeAsc(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    Optional<TravelPlan> findTopByUserIdAndStatusAndConfirmedAtIsNotNullAndArrivalDatetimeGreaterThanEqualOrderByArrivalDatetimeAsc(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime todayStart
    );

    Optional<TravelPlan> findTopByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeGreaterThanEqualOrderByArrivalDatetimeAsc(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime todayStart
    );

    List<TravelPlan> findTop5ByUserIdAndStatusAndDepartureDatetimeLessThanOrderByDepartureDatetimeDesc(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    List<TravelPlan> findTop5ByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeLessThanOrderByDepartureDatetimeDesc(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    List<TravelPlan> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndStatusAndDepartureDatetimeLessThan(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    long countByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeLessThan(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    long countByUserIdAndStatusAndDepartureDatetimeGreaterThanEqual(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );

    long countByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeGreaterThanEqual(
            Long userId,
            TravelPlanStatus status,
            LocalDateTime now
    );
}
