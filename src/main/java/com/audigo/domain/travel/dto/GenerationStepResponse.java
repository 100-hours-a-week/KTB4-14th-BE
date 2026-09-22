package com.audigo.domain.travel.dto;

import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;

public record GenerationStepResponse(
        String key,
        String label,
        TravelGenerationStageState state
) {
    public static GenerationStepResponse of(TravelGenerationStage stage, TravelGenerationStageState state) {
        return new GenerationStepResponse(jsonKey(stage), label(stage), state);
    }

    private static String jsonKey(TravelGenerationStage stage) {
        return switch (stage) {
            case PLACE_RECOMMEND -> "PLACE_RECOMMEND";
            case STAY_RECOMMEND -> "STAY_RECOMMEND";
            case ROUTE_OPTIMIZE -> "ROUTE_OPTIMIZE";
            case MUSIC_RECOMMEND -> "MUSIC_RECOMMEND";
        };
    }

    private static String label(TravelGenerationStage stage) {
        return switch (stage) {
            case PLACE_RECOMMEND -> "장소·식당 추천";
            case STAY_RECOMMEND -> "숙소 위치 계산";
            case ROUTE_OPTIMIZE -> "이동 경로 연결";
            case MUSIC_RECOMMEND -> "여행 음악 추천";
        };
    }
}
