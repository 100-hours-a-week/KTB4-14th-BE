# 여행 생성 Outbox DB 변경

## 목적

`/api/v2/travel-plans`가 여행 생성 작업을 DB에 저장한 뒤 AI Job Queue로 유실 없이 전달할 수 있도록 Outbox 이벤트를 저장한다.

## MySQL DDL

```sql
CREATE TABLE ai_generation_job_outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_job_id BIGINT NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_generation_outbox_event UNIQUE (event_id),
    CONSTRAINT uk_ai_generation_outbox_job UNIQUE (generation_job_id),
    CONSTRAINT fk_ai_generation_outbox_job
        FOREIGN KEY (generation_job_id) REFERENCES ai_generation_jobs (id),
    INDEX idx_ai_generation_outbox_pending (status, next_attempt_at),
    INDEX idx_ai_generation_outbox_processing (status, locked_at)
);
```

## 상태

- `PENDING`: 최초 발행 또는 재시도 대기
- `PROCESSING`: 발행자가 선점하여 SQS 전송 중
- `PUBLISHED`: AI Job Queue 전송 완료

`PROCESSING` 상태가 처리 임대 시간을 넘으면 다른 발행자가 다시 선점할 수 있다.

## 트랜잭션 경계

1. 여행 계획, 생성 작업, AI 요청 스냅샷, 진행 상태, Outbox 이벤트를 같은 트랜잭션에 저장한다.
2. 발행자는 전송할 이벤트를 짧은 트랜잭션으로 선점한다.
3. DB 트랜잭션 밖에서 SQS `SendMessage`를 호출한다.
4. 전송 결과를 별도 트랜잭션으로 `PUBLISHED` 또는 `PENDING`에 기록한다.

SQS 전송 성공 직후 DB 기록 전에 서버가 종료되면 같은 이벤트가 다시 발행될 수 있다. 후속 소비자는 `event_id`를 기준으로 멱등 처리해야 한다.

## 환경 변수

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `AUDIGO_SQS_OUTBOX_PUBLISHER_DELAY_MILLIS` | `1000` | 발행 주기 |
| `AUDIGO_SQS_OUTBOX_BATCH_SIZE` | `10` | 한 번에 선점할 이벤트 수 |
| `AUDIGO_SQS_OUTBOX_RETRY_BASE_DELAY_SECONDS` | `5` | 첫 재시도 대기 시간 |
| `AUDIGO_SQS_OUTBOX_RETRY_MAX_DELAY_SECONDS` | `300` | 최대 재시도 대기 시간 |
| `AUDIGO_SQS_OUTBOX_PROCESSING_LEASE_SECONDS` | `120` | 처리 중 이벤트 재선점 기준 |
