package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.ItineraryItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findAllByItineraryDayIdOrderBySequenceAsc(Long itineraryDayId);

    Optional<ItineraryItem> findByIdAndItineraryDayTravelPlanUserId(Long id, Long userId);

    void deleteAllByItineraryDayTravelPlanId(Long travelPlanId);

    @Query("""
        select item
        from ItineraryItem item
        join fetch item.travelPlanPlace planPlace
        join fetch planPlace.place
        where item.itineraryDay.id = :itineraryDayId
        order by item.sequence asc
        """)
    List<ItineraryItem> findAllWithPlaceByItineraryDayIdOrderBySequenceAsc(
            @Param("itineraryDayId") Long itineraryDayId
    );
}
