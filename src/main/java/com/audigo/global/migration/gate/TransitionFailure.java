package com.audigo.global.migration.gate;

public enum TransitionFailure {
    // audigo.migration.enabled=false 상태에서 drain
    MIGRATION_DISABLED,
    // 진행 중인 컷오버와 다른 migrationId
    MIGRATION_ID_MISMATCH,
    // 이미 지난 시도의 명령
    STALE_COMMAND,
    // 이전 시도가 끝나지 않은 상태에서 더 큰 epoch의 명령
    ATTEMPT_IN_PROGRESS,
    // 현재 단계에서 허용되지 않는 명령
    INVALID_TRANSITION,
    // 진행 중 작업이 남아 freeze 불가
    REMAINING_WORK
}
