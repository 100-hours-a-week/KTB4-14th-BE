package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelGenerationJobRepository extends JpaRepository<TravelGenerationJob, Long> {

    Optional<TravelGenerationJob> findTopByTravelPlanIdOrderByIdDesc(Long travelPlanId);

    Optional<TravelGenerationJob> findByIdAndTravelPlanUserId(Long jobId, Long userId);
}
