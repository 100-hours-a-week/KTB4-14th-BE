package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.RouteSegment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteSegmentRepository extends JpaRepository<RouteSegment, Long> {

    List<RouteSegment> findAllByTravelPlanId(Long travelPlanId);

    void deleteAllByTravelPlanId(Long travelPlanId);
}
