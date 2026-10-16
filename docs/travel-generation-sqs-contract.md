# 여행 생성 SQS 계약

## 구성

여행 생성은 서로 다른 방향과 처리 정책을 가진 두 개의 Standard Queue를 사용한다.

```text
Backend -> AI Job Queue -> AI Worker -> AI Result Queue -> Backend -> DB
```

- AI Job Queue: Backend가 AI Worker에 생성 작업을 전달한다.
- AI Result Queue: AI Worker가 Backend에 진행 상태와 최종 결과를 전달한다.
- DLQ는 사용하지 않는다. 재시도 후의 최종 실패는 작업 상태와 사용자 재생성 흐름으로 처리한다.
- Standard Queue의 최소 1회 전달 특성에 따라 메시지는 중복될 수 있다. 후속 소비자는 `event_id`와 `generation_job_id`를 멱등 키로 사용해야 한다.

## 환경 변수

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `AUDIGO_SQS_ENABLED` | `false` | SQS 연동 활성화 여부 |
| `AWS_REGION` | `ap-northeast-2` | 큐가 위치한 AWS Region |
| `AUDIGO_AI_JOB_QUEUE_URL` | 빈 값 | AI Job Queue URL |
| `AUDIGO_AI_RESULT_QUEUE_URL` | 빈 값 | AI Result Queue URL |
| `AUDIGO_SQS_ENDPOINT` | 빈 값 | 로컬 에뮬레이터를 사용할 때만 지정하는 Endpoint |

운영 환경은 Access Key를 애플리케이션 설정에 저장하지 않고 ECS Task Role을 사용한다. 로컬 환경은 AWS SDK 기본 자격 증명 체인의 프로필 또는 환경 변수를 사용한다.

## Queue 설정

### AI Job Queue

- 종류: Standard Queue
- Visibility Timeout: 960초
- Receive Wait Time: 20초
- AI Worker 수신 개수: 1개
- 삭제 시점: AI 결과를 Result Queue에 성공적으로 발행한 뒤

### AI Result Queue

- 종류: Standard Queue
- Visibility Timeout: 60초
- Receive Wait Time: 20초
- 삭제 시점: Backend의 결과 저장과 작업 상태 변경이 커밋된 뒤

## Job 메시지

AI Worker는 Backend DB를 조회하지 않으므로 `request`에 전체 AI 요청 스냅샷을 전달한다.

```json
{
  "event_id": "4c3ee3b4-7fb7-4dd7-9f11-ded3455e70f0",
  "generation_job_id": 41,
  "message_version": "1",
  "occurred_at": "2026-10-16T01:00:00Z",
  "request": {
    "travel_plan_id": 10,
    "region_id": 1,
    "region_name": "서울특별시",
    "arrival_datetime": "2026-10-20T10:00:00",
    "departure_datetime": "2026-10-22T18:00:00",
    "headcount": 2,
    "companion_type": "FRIEND",
    "preference": {},
    "required_places": []
  }
}
```

## Result 메시지

`type`은 `PROGRESS`, `COMPLETED`, `FAILED` 중 하나다.

```json
{
  "event_id": "d8f66917-1fbd-41b2-8ff0-97d694c50541",
  "generation_job_id": 41,
  "message_version": "1",
  "occurred_at": "2026-10-16T01:05:00Z",
  "type": "COMPLETED",
  "stage": "ROUTE_OPTIMIZE",
  "state": "DONE",
  "payload": {
    "days": []
  },
  "error_message": null
}
```

### 유형별 필수값

- `PROGRESS`: `stage`, `state`
- `COMPLETED`: `payload`
- `FAILED`: `error_message`

## IAM 최소 권한

### Backend

- AI Job Queue: `sqs:SendMessage`
- AI Result Queue: `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility`

### AI Worker

- AI Job Queue: `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility`
- AI Result Queue: `sqs:SendMessage`

## 후속 구현 경계

- Outbox 저장과 AI Job Queue 발행: 이슈 #135
- AI Result Queue 소비와 결과 저장: 이슈 #136
- 중복 처리와 최종 실패 복구: 이슈 #137
