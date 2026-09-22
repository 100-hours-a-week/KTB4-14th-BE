package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelPlanPlace;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPlanPlaceRepository extends JpaRepository<TravelPlanPlace, Long> {

    List<TravelPlanPlace> findAllByTravelPlanIdOrderByPlaceOrderAsc(Long travelPlanId);
}
