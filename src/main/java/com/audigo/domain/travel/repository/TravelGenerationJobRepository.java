package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationExecutionStatus;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelGenerationJobRepository extends JpaRepository<TravelGenerationJob, Long> {

    Optional<TravelGenerationJob> findTopByTravelPlanIdOrderByIdDesc(Long travelPlanId);

    Optional<TravelGenerationJob> findByIdAndTravelPlanUserId(Long jobId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from TravelGenerationJob job where job.id = :jobId")
    Optional<TravelGenerationJob> findByIdForUpdate(@Param("jobId") Long jobId);

    boolean existsByTravelPlanUserIdAndStatus(Long userId, TravelPlanStatus status);

    List<TravelGenerationJob> findByExecutionStatusAndStartedAtBefore(
            TravelGenerationExecutionStatus executionStatus,
            LocalDateTime startedAt
    );
}
