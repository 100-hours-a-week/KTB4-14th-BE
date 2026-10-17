package com.audigo.global.migration.admin;

import com.audigo.global.migration.gate.ResumeTarget;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// 관리 명령 본문. JSON 키는 전역 SNAKE_CASE 설정을 따른다 (migration_id, epoch, wait_seconds, target)
// waitSeconds는 freeze, target은 resume에서만 쓴다
public record MigrationCommandRequest(
        @NotBlank String migrationId,
        @NotNull @PositiveOrZero Long epoch,
        @PositiveOrZero @Max(600) Integer waitSeconds,
        ResumeTarget target
) {
}
