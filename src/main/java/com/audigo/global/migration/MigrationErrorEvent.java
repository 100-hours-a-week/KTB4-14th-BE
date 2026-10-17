package com.audigo.global.migration;

// 상태 조회의 오류 이력에 남길 컷오버 제어 오류. migrationId를 모르면 null로 보내고 수신 측이 현재 값으로 채운다
public record MigrationErrorEvent(String code, String detail, String migrationId) {

    public MigrationErrorEvent(String code, String detail) {
        this(code, detail, null);
    }
}
