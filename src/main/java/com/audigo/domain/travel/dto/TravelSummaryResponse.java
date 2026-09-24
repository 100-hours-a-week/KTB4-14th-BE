package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.CompanionType;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

public record TravelSummaryResponse(
        @JsonProperty("travel_plan_id")
        Long travelPlanId,
        String title,
        String destination,
        @JsonProperty("start_date")
        LocalDate startDate,
        @JsonProperty("end_date")
        LocalDate endDate,
        TravelPlanStatus status,
        @JsonProperty("companion_label")
        String companionLabel,
        @JsonProperty("cover_color")
        String coverColor
) {
    public static TravelSummaryResponse from(TravelPlan travelPlan) {
        String destination = travelPlan.getRegion().getFullName();
        return new TravelSummaryResponse(
                travelPlan.getId(),
                destination + " 여행",
                destination,
                travelPlan.getArrivalDatetime().toLocalDate(),
                travelPlan.getDepartureDatetime().toLocalDate(),
                travelPlan.getStatus(),
                companionLabel(travelPlan.getCompanionType(), travelPlan.getHeadCount()),
                "#2A9D8F"
        );
    }

    private static String companionLabel(CompanionType companionType, int headCount) {
        return switch (companionType) {
            case SOLO -> "혼자";
            case FRIEND -> headCount + "명";
            case COUPLE -> "커플";
            case FAMILY -> headCount + "명";
        };
    }
}
