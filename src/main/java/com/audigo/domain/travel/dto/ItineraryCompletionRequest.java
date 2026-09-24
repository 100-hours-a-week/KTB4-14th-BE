package com.audigo.domain.travel.dto;

import jakarta.validation.constraints.NotNull;

public record ItineraryCompletionRequest(
        @NotNull(message = "완료 여부는 필수입니다.")
        Boolean isCompleted
) {
}
