package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.ItineraryDay;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryDayRepository extends JpaRepository<ItineraryDay, Long> {

    List<ItineraryDay> findAllByTravelPlanIdOrderByDayNumberAsc(Long travelPlanId);

    void deleteAllByTravelPlanId(Long travelPlanId);
}
