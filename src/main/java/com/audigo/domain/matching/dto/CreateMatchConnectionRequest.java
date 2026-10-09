package com.audigo.domain.matching.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record CreateMatchConnectionRequest(
        @JsonProperty("target_user_id")
        @NotNull(message = "매칭 상대 사용자 ID는 필수입니다.")
        Long targetUserId
) {
}
