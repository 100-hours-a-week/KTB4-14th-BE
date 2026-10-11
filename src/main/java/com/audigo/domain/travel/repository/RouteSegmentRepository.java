package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.RouteSegment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteSegmentRepository extends JpaRepository<RouteSegment, Long> {

    List<RouteSegment> findAllByTravelPlanId(Long travelPlanId);

    void deleteAllByTravelPlanId(Long travelPlanId);

    @Query("""
    select distinct route
    from RouteSegment route
    left join fetch route.legs
    where route.travelPlan.id = :travelPlanId
    """)
    List<RouteSegment> findAllWithLegsByTravelPlanId(@Param("travelPlanId") Long travelPlanId);

}
