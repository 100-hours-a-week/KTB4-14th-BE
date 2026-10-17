package com.audigo.global.migration.classify;

public enum RequestKind {
    // 컷오버 제어와 무관한 요청 (관리 API, 헬스 체크, 프리플라이트, 매핑 없음)
    BYPASS,
    // DB 쓰기가 없는 요청
    PURE_READ,
    // 쓰기가 있거나 확인되지 않은 요청
    WRITE
}
