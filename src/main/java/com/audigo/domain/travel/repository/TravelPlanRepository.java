package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 목록 응답에 필요한 컬럼만 조회해 TravelPlan의 preference(OneToOne 역방향) 개별 조회를 피한다.
    @Query("""
            select new com.audigo.domain.travel.repository.TravelSummaryRow(
                travelPlan.id,
                region.fullName,
                travelPlan.arrivalDatetime,
                travelPlan.departureDatetime,
                travelPlan.status,
                travelPlan.confirmedAt,
                travelPlan.companionType,
                travelPlan.headCount
            )
            from TravelPlan travelPlan
            join travelPlan.region region
            where travelPlan.userId = :userId
            order by travelPlan.createdAt desc
            """)
    List<TravelSummaryRow> findSummariesByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndStatusAndConfirmedAtIsNotNullAndIdNotAndArrivalDatetimeLessThanEqualAndDepartureDatetimeGreaterThanEqual(
            Long userId,
            TravelPlanStatus status,
            Long excludedTravelPlanId,
            LocalDateTime departureDatetime,
            LocalDateTime arrivalDatetime
    );

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
