# 여행 생성 Result Inbox DB 변경

## 목적

AI Result Queue는 Standard Queue이므로 같은 `event_id`가 여러 번 전달될 수 있다. Inbox는 메시지 처리 기록을 여행 결과 반영과 같은 트랜잭션에 저장해 일정 중복 저장과 알림 중복 발송을 방지한다.

DLQ는 사용하지 않는다. 설정한 최대 수신 횟수를 초과한 메시지는 원문과 마지막 오류를 Inbox에 `FAILED`로 보관하고 Queue에서 삭제한다.

## MySQL DDL

```sql
CREATE TABLE ai_generation_result_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NULL,
    sqs_message_id VARCHAR(100) NOT NULL,
    generation_job_id BIGINT NULL,
    message_type VARCHAR(20) NULL,
    message_body LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    receive_count INT NOT NULL,
    last_error VARCHAR(1000) NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_generation_result_event UNIQUE (event_id),
    CONSTRAINT uk_ai_generation_result_sqs_message UNIQUE (sqs_message_id),
    INDEX idx_ai_generation_result_status_created (status, created_at)
);
```

`generation_job_id`에는 의도적으로 외래 키를 설정하지 않는다. 존재하지 않는 작업 ID나 파싱할 수 없는 메시지도 장애 분석을 위해 원문 그대로 보관할 수 있어야 하기 때문이다.

## 상태

- `PROCESSING`: `event_id`를 선점했고 같은 DB 트랜잭션에서 결과를 반영하는 중
- `PROCESSED`: 진행 상태, 최종 일정 또는 실패 상태 반영 완료
- `FAILED`: 최대 수신 횟수까지 처리하지 못해 원문과 오류를 보관한 상태

`PROCESSING` 저장과 결과 반영은 하나의 트랜잭션이다. 결과 반영에 실패하면 `PROCESSING` 행도 롤백되므로 Visibility Timeout 이후 같은 메시지를 다시 처리할 수 있다.

## 실패 메시지 복구

1. 아래 조회로 실패 원문과 오류를 확인한다.

```sql
SELECT id,
       event_id,
       sqs_message_id,
       generation_job_id,
       message_type,
       receive_count,
       last_error,
       created_at
FROM ai_generation_result_inbox
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

2. 배포·DB·메시지 계약 문제를 수정한다.
3. 작업 ID가 있는 실패는 기존 Result 메시지를 직접 재전송하지 않고 여행 재생성 API로 새 작업을 접수한다. 새 작업에는 새로운 `generation_job_id`와 `event_id`가 발급된다.
4. 파싱할 수 없는 메시지는 `message_body`를 기준으로 AI Worker의 발행 계약을 수정한다.

기존 작업을 그대로 되살리는 Admin 재처리는 2차 운영 기능에서 별도로 제공한다.

## 배포 순서

1. 운영 DB에 위 DDL을 적용한다.
2. Backend IAM에 기존 Result Queue 수신·삭제 권한이 있는지 확인한다.
3. 애플리케이션을 배포한다.
4. 중복 메시지와 최대 수신 횟수 초과 메시지의 Inbox 기록을 확인한다.
