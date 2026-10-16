package com.audigo.global.migration.gate;

// 재개 시점의 DB 목적지. 상태 기록과 로그용이며 백엔드 동작은 같다
public enum ResumeTarget {
    // ProxySQL 전환 완료 후 재개
    RDS,
    // 전환 취소, 원본 유지
    ORIGIN
}
