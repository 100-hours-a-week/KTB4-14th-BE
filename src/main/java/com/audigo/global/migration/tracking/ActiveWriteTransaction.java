package com.audigo.global.migration.tracking;

import java.time.Instant;

// 진행 중인 쓰기 트랜잭션 1건
public record ActiveWriteTransaction(String name, String threadName, Instant startedAt) {
}
