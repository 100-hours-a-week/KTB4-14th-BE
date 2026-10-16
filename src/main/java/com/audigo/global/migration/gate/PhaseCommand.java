package com.audigo.global.migration.gate;

// 단계 전이 명령. epoch는 같은 컷오버의 시도 번호이며 drain·freeze·resume에 같은 값을 쓴다
// 다시 시도할 때는 기존 시도를 끝내 OPEN으로 돌린 뒤 epoch를 올려 drain부터 보낸다
public record PhaseCommand(String migrationId, long epoch) {

    public PhaseCommand {
        if (migrationId == null || migrationId.isBlank()) {
            throw new IllegalArgumentException("migrationId must not be blank");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        migrationId = migrationId.trim();
    }
}
