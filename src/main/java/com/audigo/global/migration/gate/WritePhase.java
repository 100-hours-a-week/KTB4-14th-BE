package com.audigo.global.migration.gate;

// 인스턴스 쓰기 단계. OPEN → DRAINING → FROZEN → RESUMING → OPEN
public enum WritePhase {
    // 정상 동작
    OPEN,
    // 신규 쓰기 보류, 진행 중 작업 완료 대기
    DRAINING,
    // 진행 중 작업 0 확인, 추가 쓰기 금지 확정
    FROZEN,
    // 보류 요청 재개 중
    RESUMING
}
