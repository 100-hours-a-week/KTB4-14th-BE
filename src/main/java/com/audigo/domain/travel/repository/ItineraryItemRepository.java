package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.ItineraryItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findAllByItineraryDayIdOrderBySequenceAsc(Long itineraryDayId);

    Optional<ItineraryItem> findByIdAndItineraryDayTravelPlanUserId(Long id, Long userId);

    void deleteAllByItineraryDayTravelPlanId(Long travelPlanId);
}
