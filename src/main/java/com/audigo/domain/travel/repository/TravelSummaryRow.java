package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import java.time.LocalDateTime;

public record TravelSummaryRow(
        Long travelPlanId,
        String regionFullName,
        LocalDateTime arrivalDatetime,
        LocalDateTime departureDatetime,
        TravelPlanStatus status,
        LocalDateTime confirmedAt,
        CompanionType companionType,
        int headCount
) {
}
