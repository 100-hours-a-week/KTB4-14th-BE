package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPlanRepository extends JpaRepository<TravelPlan, Long> {

    Optional<TravelPlan> findByIdAndUserId(Long id, Long userId);
}
