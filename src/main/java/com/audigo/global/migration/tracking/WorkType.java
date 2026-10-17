package com.audigo.global.migration.tracking;

// 컷오버 중 완료를 기다려야 하는 작업 종류
public enum WorkType {
    // HTTP 쓰기 요청
    HTTP_WRITE,
    // 입장한 HTTP 요청 안에서 생성되는 AI 작업
    AI_JOB,
    // @Scheduled 주기 작업
    SCHEDULED,
    // 인증 처리 등 내부 쓰기
    INTERNAL
}
