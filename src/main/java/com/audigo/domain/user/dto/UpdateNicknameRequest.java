package com.audigo.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(
        @NotBlank
        @Size(min = 2, max = 5)
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$")
        String nickname
) {
    public UpdateNicknameRequest {
        if (nickname != null) {
            nickname = nickname.replaceAll("\\s+", "");
        }
    }
}
