# SQS Result 처리 지연 관측

## 목적과 적용 범위

SQS-05에서는 여행 생성 10건이 모두 완료됐지만, 일부 결과가 Mock의 최종 전송 성공 후 BE 완료 로그까지 60~122초 지연됐다. 작업 2258의 완료 메시지는 `receive_count=3`이었고, 성공한 Inbox 처리 구간은 약 55ms였다. 이 기록만으로 이전 수신 시도, 실행기 대기, 파싱, DB 잠금, 커밋을 구분할 수 없어 관측 로그를 추가한다. 지연 원인을 해결했다고 주장하는 변경은 아니다.

수신 동시성, 배치 크기, 폴링 주기, 재시도·삭제 정책 및 메시지 계약은 유지한다. DB 마이그레이션, 추가 IAM 권한, 신규 환경변수는 필요하지 않다. API 및 프론트엔드 경로도 변경하지 않는다. 패키지 버전은 유지하며 현재 운영 CD의 `sha-<commit SHA>` 이미지 식별 방식을 따른다. 이 브랜치의 푸시는 배포 실행을 의미하지 않는다.

## 로그와 시간의 의미

| 로그 / stage | 확인할 내용 |
| --- | --- |
| `travel_generation_result_consumer_config` | 실제 주입된 Result 큐 URL, 소비 동시성, 실행기 용량, 배치 크기, long polling 시간, 최대 수신 횟수 |
| `travel_generation_result_received` | SDK 수신 응답 직후 메시지 ID, UTC 수신 시각, 수신 횟수, SQS 전송·첫 수신 timestamp, UTF-8 본문 바이트 수 |
| `travel_generation_result_processing_started` | 내부 처리 진입 시점 및 `executor_wait_ms` |
| `travel_generation_result_parsed` | 파싱 성공 시 event/job/type 연결, `parse_ms`, `executor_wait_ms` |
| `stage=transaction` | Inbox 서비스의 Spring 트랜잭션 프록시 호출 전체. 트랜잭션 시작·연결 확보와 커밋까지 포함 |
| `stage=inbox_lookup` | event ID 중복 조회 |
| `stage=inbox_insert` | Inbox `saveAndFlush` |
| `stage=result_apply` | Job 서비스 호출 전체. 행 잠금, 진행 상태, 결과 해석·저장, 알림 처리 등을 포함 |
| `stage=job_lock` | 작업 조회 및 비관적 쓰기 잠금 획득. event/job ID로 연결하며 이 로그의 message ID·수신 횟수는 null |
| `stage=commit_phase` | `beforeCommit`부터 `afterCompletion`까지. flush 및 트랜잭션 콜백을 포함하므로 순수 DB COMMIT 명령의 시간이 아님 |
| `stage=delete` | SQS DeleteMessage 호출 |
| `travel_generation_result_processing_finished` | 서비스 호출과 삭제 성공 이후 `receive_to_delete_ms`, PROCESSED/DUPLICATE 결과 |

각 stage는 작업 **이전**에 `travel_generation_result_stage_started`, 종료·예외 발생 시 `travel_generation_result_stage_finished`를 남긴다. 시작 로그만 남으면 그 구간에서 대기하거나 프로세스가 종료됐을 가능성을 조사한다. 실패 구간은 `outcome=FAILURE`, 예외 종류를 기록하고 원래 예외를 그대로 전파한다. 커밋 콜백은 `COMMITTED`, `ROLLED_BACK`, `UNKNOWN`으로 결과를 구분한다. `beforeCommit` 전에 롤백된 경우 커밋 구간 시간은 `-1`이다.

- `executor_wait_ms`는 SDK 응답 이후 처리 함수 진입까지의 시간이다. 로그 기록·작업 제출·스케줄링을 포함하며 CallerRunsPolicy의 poller 직접 처리에도 같은 기준을 적용한다. 전체 배치의 수신 로그를 작업 제출 전에 기록한다.
- `sent_to_received_ms`는 SQS 전송부터 **이번** BE 수신까지의 경과 시간이다. 이전 수신·visibility 대기·재전달을 포함할 수 있어 순수 큐 대기 시간으로 부르지 않는다. 서버와 SQS의 시각 차이도 고려한다.
- `sent_timestamp_ms`와 `first_receive_timestamp_ms`는 epoch milliseconds다. 속성이 없거나 잘못된 경우 `-1`을 기록하고 처리 흐름은 계속한다. 시각 역전으로 음수가 되는 `sent_to_received_ms`도 `-1`로 구분한다.
- 로컬 구간 시간은 `System.nanoTime()`을 사용한 경과 시간이며 CPU 사용 시간은 아니다. 로그 기록 비용도 일부 포함한다.
- 중첩 stage를 더하지 않는다. 예를 들어 `transaction` 안에 `result_apply`와 `job_lock`이 포함된다. 수신 이후 JSON 파싱 외에도 결과 해석 과정에서 JSON 처리가 발생하므로 `parse_ms`가 전체 JSON 비용은 아니다.
- `travel_generation_completed`는 기존과 같이 커밋 전 로그다. 커밋 결과는 `commit_phase` 및 서비스 프록시 호출 성공으로 판단하고, 삭제 성공도 함께 확인한다.
- 메시지 원문, access token, receipt handle은 신규 관측 로그에 기록하지 않는다. 크기는 최종 SQS 본문의 UTF-8 바이트 수다.
- `receive_count`는 SQS의 ApproximateReceiveCount이며 BE 처리 실패 횟수와 같지 않다. 표준 큐 중복 전달, 다른 소비자·콘솔 수신, SDK 수신 재시도 가능성을 로그와 함께 구분한다.

## 배포 후 재측정

1. 인프라 작업 완료 후 배포 커밋 SHA, BE·Mock 태스크/프로세스 수와 설정을 기록한다. 인프라가 이전 실험과 달라졌다면 변경 전후의 성능 차이를 이 코드만의 효과로 해석하지 않는다.
2. Mock 동시성 3·지연 30초와 BE 설정을 유지한다. 기존 작업과 Job·Result 큐가 해소됐는지 확인한다. 동시성에는 설정한 실행기 외에 CallerRunsPolicy의 poller 직접 처리가 개입할 수 있다.
3. 기존 P-01의 VU 10·총 10건을 한 번 재실행한다. 이번 계측 배포 검증에서 VU를 동시에 늘리지 않는다.
4. 요청 접수·완료·일정 조회 10건을 확인하고, 늦은 작업을 event/message ID 및 receive count별로 추적한다. job ID만으로 묶으면 진행 이벤트와 완료 이벤트가 섞인다.
5. 지연 구간을 설명하거나 수정한 다음 VU 20·30·50 단계로 진행한다. SQS 콘솔에서 메시지 자체를 폴링하지 않고 모니터링 그래프를 관찰한다.

로그는 애플리케이션이 실제로 받은 시점부터 관측한다. 관측되지 않은 과거 수신이나 SQS 내부 동작까지 복원하지는 못한다. 수신 횟수가 증가했는데 BE 수신 이력이 없다면 큐 URL, 배포 버전, 전체 BE 태스크/스트림과 외부 수신 여부를 확인한다.

## CloudWatch Logs Insights

`/ecs/audigo-staging-backend` 로그 그룹의 **전체 스트림**을 선택한다. 현지 시간 KST 09:34~09:41은 UTC 00:34~00:41이다. 자동 생성된 SOURCE에 `09:34Z`를 넣으면 KST 18:34를 검색하므로 그래프의 실제 시간도 확인한다. 아래 쿼리는 콘솔이 생성한 SOURCE 다음에 사용한다.

### 특정 작업의 파싱 이후 경로

```sql
fields @timestamp, @logStream, @message
| filter @message like /job_id=2258([^0-9]|$)/
| sort @timestamp asc
| limit 1000
```

`parsed`에서 message ID를 찾은 후 같은 message ID를 검색하면 job ID를 알 수 없었던 수신·실행 시작 로그도 연결할 수 있다. 재수신마다 receive count 및 로그 스트림을 함께 비교한다.

### 성공한 stage의 소요 시간

```sql
fields @timestamp, @logStream, @message
| filter @message like /travel_generation_result_stage_finished/
| parse @message /stage=(?<stage>\S+)/
| parse @message /duration_ms=(?<duration_ms>[0-9.]+)/
| parse @message /outcome=(?<outcome>\S+)/
| filter outcome in ["SUCCESS", "COMMITTED"]
| stats count(*) as samples, avg(duration_ms) as avg_ms,
        pct(duration_ms, 95) as p95_ms, max(duration_ms) as max_ms by stage
```

### 예외 및 커밋 실패

```sql
fields @timestamp, @logStream, @message
| filter @message like /travel_generation_result_(receive_failed|parse_failed|process_failed|archive_failed)/
    or @message like /outcome=(FAILURE|ROLLED_BACK|UNKNOWN)/
| sort @timestamp asc
| limit 1000
```

첫 조회가 0건이면 filter를 제거해 정상 로그가 존재하는지 확인한다. 검색 범위가 맞는지 확인하는 절차이며, 0건 자체가 전체 시스템에 문제가 없다는 증거는 아니다.
