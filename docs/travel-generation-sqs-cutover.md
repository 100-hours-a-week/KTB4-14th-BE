# 여행 생성 SQS 전환 및 롤백 절차

## API 실행 경로

| API | 접수 이후 실행 경로 | 용도 |
| --- | --- | --- |
| `POST /api/travel-plans` | 기존 BE 내부 `TravelGenerationWorker` | main-v1 및 장애 시 신규 요청 우회 |
| `POST /api/v2/travel-plans` | DB Job/Payload/Progress/Outbox -> AI Job Queue | main-v2 |
| `POST /api/travel-plans/{id}/regenerate` | 기존 BE 내부 `TravelGenerationWorker` | main-v1 재생성 |
| `POST /api/v2/travel-plans/{id}/regenerate` | DB Job/Payload/Progress/Outbox -> AI Job Queue | main-v2 재생성 |

두 버전은 모두 `202 Accepted`와 `travel_plan_id`, `generation_job_id`를 반환한다. 상태 조회는
`GET /api/ai-generation-jobs/{generationJobId}`를 공통으로 사용한다.

## 배포 전 확인

1. `travel-generation-job-persistence-migration.md`, `travel-generation-outbox-migration.md`,
   `travel-generation-result-inbox-migration.md`의 DDL을 먼저 반영한다.
2. AI Job Queue와 AI Result Queue가 Standard Queue로 생성되어 있는지 확인한다.
3. BE에는 Job Queue 전송 및 Result Queue 수신 권한을, AI Worker에는 반대 방향 권한을 부여한다.
4. BE와 AI Worker에 동일한 메시지 계약 버전을 배포한다.
5. BE에 다음 환경 변수를 설정하고 기동한다.

```bash
AUDIGO_SQS_ENABLED=true
AWS_REGION=ap-northeast-2
AUDIGO_AI_JOB_QUEUE_URL=<job-queue-url>
AUDIGO_AI_RESULT_QUEUE_URL=<result-queue-url>
```

## 기능 검증

1. v1 생성·재생성 요청이 기존 내부 worker에서 완료되는지 확인한다.
2. v2 생성·재생성 요청 후 Outbox가 `PUBLISHED`, Job이 `COMPLETED`로 변경되는지 확인한다.
3. 같은 Result 메시지를 중복 전송해 일정과 알림이 한 번만 반영되는지 확인한다.
4. 처리 중 BE를 재시작해 Result Queue 메시지가 재수신되고 완료되는지 확인한다.
5. 처리 중 AI Worker를 재시작해 Job Queue 메시지가 재수신되고 결과가 반환되는지 확인한다.
6. 파싱 불가 또는 최대 재시도 초과 Result가 Inbox의 `FAILED`로 남는지 확인한다.

## v2 부하 검증

기존 P-01 시나리오는 `P01_CREATION_PATH`로 v1과 v2를 선택한다. 기본값은 v1이다.

```bash
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
P01_CREATION_PATH=/api/v2/travel-plans \
P01_RAMP_VU_STAGE_1=10 \
P01_RAMP_VU_STAGE_2=30 \
P01_RAMP_VU_PEAK=50 \
./scripts/test/run-k6.sh scenarios/p01/ramping-vus.js
```

아래 항목을 같은 시간축으로 기록한다.

- k6: 생성 성공·실패 수, 생성 완료 시간 p95/p99, HTTP 실패율
- Outbox: 미발행 수, 재시도 수, 가장 오래된 미발행 이벤트 시간
- SQS: 전송·수신·삭제 수, 가시 메시지 수, 가장 오래된 메시지 시간
- Result Inbox: `FAILED` 수와 실패 원인
- BE: CPU, JVM heap/GC, HTTP thread, Hikari active/pending

## 전환

1. 먼저 v1과 v2를 모두 사용할 수 있는 BE를 배포한다.
2. 기능·복구·부하 검증을 통과한 뒤 main-v2 FE의 생성·재생성 API만 `/api/v2/travel-plans`로 전환한다.
3. v1 API와 내부 worker는 롤백 경로로 유지한다.

## 롤백

1. main-v2 신규 요청을 중단하고 FE를 main-v1 또는 v1 API로 전환한다.
2. 이미 접수된 v2 작업이 남아 있으므로 즉시 `AUDIGO_SQS_ENABLED=false`로 바꾸지 않는다.
3. Outbox 미발행 건수와 두 SQS 큐의 대기·처리 중 메시지가 0인지 확인한다.
4. v2 Job의 `QUEUED`·`PROCESSING` 잔여 건이 없는지 확인한다.
5. 잔여 작업이 모두 끝난 뒤에만 필요 시 SQS publisher/consumer를 비활성화한다.

이 순서를 지켜야 롤백 시점에 이미 접수된 v2 작업과 결과를 유실하지 않는다.
