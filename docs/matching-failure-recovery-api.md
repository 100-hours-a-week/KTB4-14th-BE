# 매칭 실패 복구 API

## 현재 세션 확인

`GET /api/v2/matching-requests/me`는 AI를 호출하지 않고 현재 세션 상태를 반환한다.
기존 응답에 `can_retry`를 추가한다. DB 테이블 및 컬럼 변경은 없다.

| latest_attempt_status | has_result | can_retry | FE 처리 기준 |
| --- | --- | --- | --- |
| null | false | false | 최초 후보 조회로 시작 |
| PROCESSING | false 또는 true | false | 진행 상태 유지, 중복 실행 금지 |
| SUCCEEDED | true | false | 저장된 후보 조회, 0명이면 조건 불일치 안내 |
| FAILED | false | true | 첫 매칭 실패: 다시 시도 또는 조건 변경 |
| FAILED | true | true | 재매칭 실패: 기존 성공 결과 유지, 실패한 시도 재시도 가능 |

`can_retry`는 ACTIVE 요청의 최신 시도가 FAILED일 때만 true다.
`has_result`는 후보 수가 아니라 성공한 시도의 존재 여부다. 후보 0명 응답도 SUCCEEDED로 저장된다.
CONNECTED 또는 CANCELLED인 최신 요청은 기존 정책대로 404를 반환한다.

## 실패한 시도 재실행

```http
POST /api/v2/matches/candidates/retry
```

요청 본문은 없다. 현재 로그인 사용자의 최신 ACTIVE 요청에서 실패한 시도만 재실행한다.

- 첫 매칭 실패: 동일 요청에 retry_sequence=0인 새 시도를 생성한다.
- 재매칭 실패: 기존 성공 후보와 현재 연결 상대를 제외하고 같은 재매칭 순번으로 다시 실행한다.
- 실패한 시도는 이력으로 보존한다. 실패 자체는 retry_count를 증가시키지 않는다.
- 재매칭 재시도가 성공하면 retry_count를 한 번 증가시킨다. 최초 시도 재시도는 증가시키지 않는다.
- 성공 응답은 기존 후보 API와 동일한 candidates, count, retry_count, remaining_retry_count, can_rematch를 반환한다.
- 요청이 없거나 종료된 경우: 404 matching_request_not_found.
- 진행 중인 시도가 있는 경우: 409 matching_rematch_in_progress.
- 최신 시도가 실패가 아닌 경우: 409 matching_retry_not_available.

기존 `GET /api/v2/matches/candidates`는 성공 결과가 있으면 복원한다.
성공 결과가 없는 요청에서는 최초 시도를 시작할 수 있으므로 단순 상태 확인에는 사용하지 않는다.
기존 `POST /api/v2/matches/candidates/rematch`는 정상적인 다음 재매칭에 사용한다.

## 조건 변경 및 취소

```http
DELETE /api/v2/matching-requests/me
```

- 최신 ACTIVE 또는 EXHAUSTED 요청을 CANCELLED로 변경한다.
- 진행 중인 시도는 FAILED로 마무리하고 failure_reason에 matching_request_cancelled를 남긴다.
- 요청과 시도 이력, 이전 성공 후보는 삭제하지 않는다.
- 취소 성공(204)을 확인한 뒤 FE의 조건 및 후보 데이터를 초기화하고 새 요청을 생성한다.
- 취소 후 상태 조회와 후보 조회는 404를 반환한다.
- 새 조건 입력 화면으로 자동 이동하는 UX는 후속 FE 작업에서 적용한다.

## 취소와 AI 결과 수신이 겹칠 때

시도 준비, 재시도, 완료, 실패, 취소는 매칭 요청의 비관적 락을 먼저 확보한다.
AI 통신은 DB 트랜잭션 밖에서 실행한다.

- 취소가 먼저 완료되면 늦게 도착한 결과는 저장하지 않고 404를 반환한다.
- 결과 저장이 먼저 완료되면 이후 취소는 세션만 종료하고 성공 이력을 유지한다.
- 취소는 실행 중인 외부 AI 요청 자체를 중단시키지는 않는다.
- 프로필 조회 또는 AI 통신 등이 실패하면 시도를 FAILED로 기록해 PROCESSING에 남지 않도록 한다.

서버 프로세스 강제 종료로 남은 PROCESSING 시도의 자동 복구 및 만료 정책은 이번 범위에 포함하지 않는다.
