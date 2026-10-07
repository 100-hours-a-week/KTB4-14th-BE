# Audigo Staging 부하테스트

Spring Boot 소스는 변경하지 않고 `load-test/`만 사용해 **Staging Backend와 MySQL**을 검증한다.
운영 API `https://api.audigo.kr`는 코드에서 차단한다. CPU·메모리·p95·5xx는 자동 감속 조건이 아니라
**한계점 분석을 위한 기록값**이다.

## 빠른 실행 명령어

모든 명령은 `load-test/`에서 실행한다. 각 줄 끝의 `\` 뒤에는 공백을 넣지 않는다. 결과는
`results/YYYY-MM-DD-HH-mm/`에 저장되고, 하위 시나리오는 `itinerary-ramping-arrival-summary.json`처럼
폴더명을 포함한 이름으로 구분된다.

### 단일 API Ramp 부하테스트 — 신규 9개

기존 사용자 흐름 테스트는 유지한다. 아래 시나리오는 `ramping-arrival-rate`로 **측정 iteration당 대상 API만
1회 호출**한다. 인증 preflight·`setup()` 준비 요청은 측정 전 별도 요청이다. 부하 주입 방식 옵션화는 하지 않는다.
알림 단건/여러 건/전체 읽음 처리와 알림 설정 PATCH는 이번 추가 대상이 아니다.

| 파일 (`scenarios/api/`) | 대상 API | 환경변수 접두사 |
| --- | --- | --- |
| `upcoming-ramping-arrival.js` | `GET /api/travel-plans/upcoming` | `UPCOMING_RAMP` |
| `recent-ramping-arrival.js` | `GET /api/travel-plans/recent` | `RECENT_RAMP` |
| `itinerary-ramping-arrival.js` | `GET /api/travel-plans/{id}/itinerary` | `ITINERARY_API_RAMP` |
| `regions-ramping-arrival.js` | `GET /api/regions` | `REGIONS_RAMP` |
| `generation-job-ramping-arrival.js` | `GET /api/ai-generation-jobs/{id}` | `GENERATION_JOB_RAMP` |
| `unread-count-ramping-arrival.js` | `GET /api/notifications/unread-count` | `UNREAD_COUNT_RAMP` |
| `completion-ramping-arrival.js` | `PATCH /api/itinerary-items/{id}/completion` | `COMPLETION_API_RAMP` |
| `travel-plan-create-ramping-arrival.js` | `POST /api/travel-plans` | `CREATE_API_RAMP` |
| `sse-ramping-arrival.js` | `GET /api/notifications/subscribe` | `SSE_API_RAMP` |

#### 6개 읽기 API: 각각 독립 실행

```bash
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/upcoming-ramping-arrival.js

CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/recent-ramping-arrival.js

CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/itinerary-ramping-arrival.js

CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/regions-ramping-arrival.js

CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/generation-job-ramping-arrival.js

CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/unread-count-ramping-arrival.js
```

| 설정 | 설명 |
| --- | --- |
| `CONFIRM_STAGING=true` | 필수. 운영 호스트는 차단한다. |
| 기본 프로파일 | 시작 1 → 10 → 30 → 50 RPS, 50 RPS 유지 → 1 RPS 감소. 총 4분. |
| 데이터 | 사용자별 JWT. 일정 조회는 `travelPlanId`, Job 조회는 `generationJobId`가 필요하다. |
| 요청 수 | iteration당 1개이므로 flow/min 환산이 없다. 목표 RPS와 실제 RPS는 구분한다. |

#### 완료 PATCH만 반복 토글 + 종료 후 리셋

```bash
# 성능 측정만 실행: 종료 후 아래 수동 리셋이 필요하다.
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-k6.sh scenarios/api/completion-ramping-arrival.js

# 수동 리셋: 출력된 실제 run-id를 넣는다.
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/reset-completions.sh \
  --items results/<run-id>/completion-items.json

# 성능 측정 + 마지막 자동 리셋
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-completion-with-reset.sh \
  scenarios/api/completion-ramping-arrival.js
```

| 옵션/결과 | 설명 |
| --- | --- |
| `ALLOW_WRITE_TESTS`, `CONFIRM_COMPLETION_RESET` | 모두 `true` 필수. |
| `COMPLETION_API_RAMP_MAX_VUS` | 기본 100. 사용자별 항목을 VU에 고정하며, 데이터 수 초과·중복 항목은 시작 전에 거절한다. |
| `COMPLETION_API_SETUP_TIMEOUT` | 기본 `5m`. 준비 단계에서 최대 VU에 배정된 항목의 초기 상태를 GET으로 확인한다. |
| 측정 중 요청 | PATCH만 호출. 응답 ID·상태를 확인한 성공 뒤 `true ↔ false` 교대한다. |
| 실패/Timeout | 다음 요청은 같은 목표값으로 재동기화한다. 재동기화 성공은 실제 전환 성공으로 집계하지 않는다. |
| custom metrics | `completion_api_attempts`, `completion_false_to_true`, `completion_true_to_false`, `completion_state_uncertain`, `completion_state_reconfirmed` |
| `completion-items.json` | 응답 전에 기록한 항목별 시도 metric으로 실제 요청한 userId·itemId만 저장한다. JWT는 포함하지 않는다. |
| 자동 복구 | 실패 실행이라도 목록이 있으면 대상만 `false`로 복구한다. 목록이 없거나 복구 실패 시 전체 테스트 항목으로 fallback한다. |
| 복구 제한 | SIGKILL·전원 종료에서는 자동 리셋을 보장하지 못한다. 실행 종료·최종 리셋은 동시 테스트가 없는 상태에서 수행한다. |

기존 `completion_attempts = 앞 N개 항목` 방식은 새 반복 시나리오에 사용하지 않는다.
`--items`는 `--summary`, `--count`, `--user-id`와 함께 사용할 수 없다. 잘못된 사용자·항목 조합은 복구하지 않는다.
기본 100개 준비 조회는 전체 HTTP 합계에 포함되므로 POST/PATCH 병목 분석에는 아래 `phase:api_measurement` 지표를 사용한다.

#### 여행 생성 POST: 고유 사용자당 1회, 시작률 증가

```bash
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
./scripts/test/run-k6.sh \
  scenarios/api/travel-plan-create-ramping-arrival.js
```

| 옵션/결과 | 설명 |
| --- | --- |
| `CONFIRM_AI_MOCK=true` | 필수. 실제 AI·카카오를 막는 Staging Mock 라우팅은 배포 환경에서 별도로 확인한다. 플래그만으로 외부 호출이 차단되는 것은 아니다. |
| 기본 프로파일 | 1 → 3 → 5 → 10건/초 → 10건/초 유지 → 1건/초. 각 10초, 총 50초. |
| 데이터 한도 | 약 290건 + 경계 예약 여유 1개 = 291명의 고유 사용자를 검증한다. 300명 준비 시 기본 실행 가능. |
| 배정 | 전체 iteration 번호로 고유 사용자를 배정하고 같은 사용자를 재사용하지 않는다. 런타임 데이터 소진도 명확히 중단한다. |
| 준비 단계 | 사용할 사용자의 `/api/travel-plans/me`를 조회한다. `GENERATING` 여행·인증 오류가 있으면 POST 부하를 시작하지 않는다. 동시 다른 실행은 금지한다. |
| `CREATE_API_SETUP_TIMEOUT` | 기본 `5m`. 준비 조회 전체의 시간 상한. |
| 측정 범위 | POST 접수와 `202`·반환 ID를 확인한다. 측정 중 Job 폴링·일정 조회는 하지 않는다. |
| 결과 기록 | `generation-results.json`에 userId·travelPlanId·generationJobId 기록. 실패 또는 응답을 확인하지 못하면 ID는 null일 수 있다. |
| 후속 확인 | 결과의 Job ID로 테스트 후 상태·실패를 확인한다. null ID는 실행 ID·사용자와 서버 로그로 추적한다. summary만으로 비동기 완료·성공을 확정하지 않는다. |
| 데이터 정리 | 기록된 ID와 테스트 사용자 소유권을 확인한 뒤 Staging에서만 정리한다. 참조 관계가 있으므로 이 테스트는 DB 자동 삭제를 수행하지 않는다. 기존 시딩 여행 ID는 변경하지 않는다. |

단계별 예상 생성 수는 `Σ((이전 rate + 목표 rate) / 2 × 단계 초)`로 계산한다. `ceil(합계) + 1`이
사용자 수를 넘으면 요청 전 실패한다. 여러 번 실행하려면 이전 Job이 끝난 뒤 다시 실행한다.
**300명이란 300 VU 또는 300 RPS라는 뜻이 아니며**, 이 짧은 프로파일만으로 장시간 지속 처리량을 확정하지 않는다.

#### SSE: 신규 연결 시작률 증가 + 지정 시간 유지

```bash
CONFIRM_STAGING=true \
SSE_API_HOLD_DURATION=10s \
./scripts/test/run-k6.sh scenarios/api/sse-ramping-arrival.js
```

| 옵션/결과 | 설명 |
| --- | --- |
| rate 단위 | HTTP 완료 RPS가 아니라 **신규 구독 연결 시작 수/초**. 기본 1 → 2 → 3 → 5 → 5 유지 → 1 감소. |
| `SSE_API_HOLD_DURATION` | 기본 `10s`. 연결 요청 시작부터의 전체 수명 기한이며 handshake 시간도 포함한다. |
| VU | 기본 사전 60·최대 100. VU별 전용 사용자, 사용자 수 초과는 시작 전 차단한다. 동시에 같은 사용자의 구독이 겹치지 않는다. |
| 연결 수 | 대략 시작률 × 유지 시간. 연결이 길수록 필요한 VU가 증가하고 부족하면 `dropped_iterations`가 생긴다. |
| 호출 범위 | subscribe만 요청하며 unread-count를 호출하지 않는다. 이벤트 수신도 기록한다. |
| 정상 기한 종료 | `connected` 수신 후 설정 기한의 timeout은 `sse_session_duration_expired`로 집계하고 연결 오류에서 제외한다. |
| 실제 오류 | 기한 전 종료·연결 실패·connected 미수신은 `sse_connection_errors`로 집계한다. |
| 종료 여유 | gracefulStop을 수명 기한 + 5초로 설정한다. 강제 중단된 연결은 정상 종료 지표를 남기지 못할 수 있다. |

고정 버전 [xk6-sse v0.1.11](https://github.com/phymbert/xk6-sse/blob/v0.1.11/sse.go)은 구독 중 JS 이벤트 루프를
차단한다. 따라서 JS 타이머로 종료하지 않고 HTTP 수명 기한을 사용하며 예상 timeout과 실제 오류를 분리한다.
기존 `sse/constant-vus.js`·`sse/ramping-vus.js`의 동작은 유지한다.

#### 공통 옵션과 결과 해석

각 API의 접두사 `<PREFIX>`를 앞 표에서 선택한다.

| 환경변수 | 일반 읽기·완료 기본값 | 생성 POST 기본값 | SSE 기본값 |
| --- | --- | --- | --- |
| `<PREFIX>_START_RPS` | 1 | 1 | 1 |
| `<PREFIX>_STAGE_1_RPS` | 10 | 3 | 2 |
| `<PREFIX>_STAGE_2_RPS` | 30 | 5 | 3 |
| `<PREFIX>_PEAK_RPS` | 50 | 10 | 5 |
| `<PREFIX>_STAGE_1_DURATION` | 30s | 10s | 30s |
| `<PREFIX>_STAGE_2_DURATION` | 30s | 10s | 30s |
| `<PREFIX>_UP_DURATION` | 30s | 10s | 30s |
| `<PREFIX>_PEAK_DURATION` | 2m | 10s | 2m |
| `<PREFIX>_DOWN_DURATION` | 30s | 10s | 30s |
| `<PREFIX>_PRE_ALLOCATED_VUS` | 10 | 10 | 60 |
| `<PREFIX>_MAX_VUS` | 100 | 100 | 100 |

`PRE_ALLOCATED_VUS <= MAX_VUS`가 필요하다. Stage target은 이 구현에서는 양의 정수만 허용한다.
기존 6개 단일 API Ramp 파일과 사용자 흐름 프로파일은 계속 사용할 수 있다.

신규 일반 API summary에서 다음 submetric은 준비 GET을 제외한 대상 API만 집계한다.

- `http_reqs{phase:api_measurement}`
- `http_req_duration{phase:api_measurement}`
- `http_req_failed{phase:api_measurement}`

submetric의 요청 수·지연은 준비 요청을 제외하지만 summary의 `rate` 계산 시간에는 setup 시간이
포함될 수 있다. 단계별 실제 RPS는 Dashboard/시계열의 측정 구간을 확인한다.

성능 중단 기준이 아닌 항상 통과하는 threshold로 submetric을 노출한다. 준비 단계에는
`phase:api_preparation`을 사용하며, 측정 중 executor의 `iterations`·`dropped_iterations`도 확인한다.
SSE는 전용 `sse_connection_attempts`·`sse_connection_errors`·`sse_events_received`와 기한 종료 지표를 사용한다.
`http_req_failed`만으로 응답 본문 오류까지 판단하지 말고 checks·API 결과를 함께 확인한다.

#### 로컬 검증 (Staging·외부 API 호출 없음)

```bash
node scripts/test/single-api-local-smoke.mjs
```

| 검증 | 범위 |
| --- | --- |
| 실행 대상 | 127.0.0.1 Mock 서버와 임시 가짜 사용자 데이터만 사용. 실제 `.env`·JWT·운영 DB를 사용하지 않는다. |
| 신규 9개 | inspect로 executor·stage 검증 후 낮은 요청률로 단일 API 호출 검증 |
| 완료 처리 | 정상 교대·503 이후 재동기화·실제 항목 복구·실패 실행 자동 복구/fallback |
| 생성 | 고유 사용자·반환 ID 기록·사용자 한도·진행 중 생성·403 차단 |
| SSE | subscribe만 호출·이벤트 수신·수명 기한 종료·동시 사용자 중복 없음 |
| 기존 테스트 | 기존 단일 API 6개와 사용자 흐름 시나리오 inspect 회귀 검증 |

로컬 검증은 실제 Staging 배포·인증·Mock 라우팅 검증을 대체하지 않는다. 실제 환경에서는 stage를 짧게 하고
모든 rate를 1로 낮춘 뒤 실행한다. 생성은 총 예약 수에 맞는 고유 사용자를 준비한다. 낮은 부하 Staging 결과를
확인하기 전에는 위 기본/고부하 명령을 검증 완료로 간주하지 않는다.

### 모든 실행의 k6 Web Dashboard

`scripts/test/run-k6.sh`를 경유하는 모든 부하테스트는 Web Dashboard를 기본 활성화한다. 테스트가
실행 중인 동안 브라우저에서 `http://127.0.0.1:5665`를 열어 실시간 RPS·VU·응답시간·오류율을 확인한다.
실행이 끝나면 Dashboard 서버도 종료되므로, 결과는 `results/`의 summary JSON으로 확인한다.

```bash
# 기본 Dashboard 포트(5665)가 이미 사용 중일 때만 변경
CONFIRM_STAGING=true \
K6_WEB_DASHBOARD_PORT=5666 \
./scripts/test/run-k6.sh scenarios/smoke.js

# CI 등에서 Dashboard를 명시적으로 끄기
CONFIRM_STAGING=true \
K6_WEB_DASHBOARD=false \
./scripts/test/run-k6.sh scenarios/smoke.js
```

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `K6_WEB_DASHBOARD` | `true` | 모든 표준 실행의 Dashboard 활성화 여부 |
| `K6_WEB_DASHBOARD_HOST` | `127.0.0.1` | Dashboard 바인딩 주소 |
| `K6_WEB_DASHBOARD_PORT` | `5665` | Dashboard 포트. 병렬 실행 시 실행별로 다르게 지정한다. |
| `K6_WEB_DASHBOARD_PERIOD` | `1s` | Dashboard 갱신 주기 |
| `K6_WEB_DASHBOARD_OPEN` | 미설정 | `true`이면 기본 브라우저를 자동으로 연다. 창이 열려 있으면 k6 종료가 지연될 수 있어 기본으로 켜지 않는다. |

### 매 실행 전: JWT와 완료 데이터 준비

```bash
cd load-test
read -rs STAGING_JWT_SECRET
export STAGING_JWT_SECRET
printf '\n'
node scripts/initData/generate-test-tokens.mjs --ttl 86400
unset STAGING_JWT_SECRET
node scripts/test/validate-test-data.mjs --min-token-ttl-seconds 1800

CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/reset-completions.sh
```

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `STAGING_JWT_SECRET` | Staging 전용 Secret | `.env`·Git에 저장하지 않는다. Secret 교체 뒤에는 JWT를 다시 발급한다. |
| `--ttl 86400` | 24시간 | 사용자별 Access Token 유효 시간이다. |
| `--min-token-ttl-seconds 1800` | 30분 | 실행 전 요구하는 JWT 최소 잔여 유효 시간이다. |
| `CONFIRM_STAGING=true` | 필수 | Staging 실행을 명시한다. |
| `ALLOW_WRITE_TESTS=true` | 쓰기에서 필수 | 완료·생성 요청을 허용한다. |
| `CONFIRM_COMPLETION_RESET=true` | 복구에서 필수 | 완료 상태를 `false`로 복구하는 요청을 허용한다. |

### Smoke와 고정 Baseline

```bash
# 읽기 Smoke
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/smoke.js

# Smoke에서 완료 처리까지 확인하고 1개 수동 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
RUN_COMPLETION_SMOKE=true \
./scripts/test/run-k6.sh scenarios/smoke.js

CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/reset-completions.sh --count 1

# Smoke → LT-01 → LT-05 → LT-02 → LT-06 전체 Baseline (LT-02 자동 복구 포함)
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-baseline-suite.sh
```

| 명령 / 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `RUN_COMPLETION_SMOKE=true` | `false` | Smoke에 LT-02 쓰기·재조회를 추가한다. |
| `run-baseline-suite.sh` | 약 16~18분 | 고정 요청률 Baseline, SSE handshake Smoke, SSE 연결 유지 검증을 순차 실행하고 완료 항목을 자동 복구한다. |
| `ITINERARY_READ_DURATION`, `GENERATION_POLL_DURATION`, `COMPLETION_DURATION`, `SSE_DURATION` | 각 5m/5m/5m/1m | Baseline 실행 시간을 명령 앞 환경변수로 바꿀 수 있다. |

### API 직접 Ramp: 단일 API 병목 확인

기존 LT·P 프로파일은 실제 사용자 흐름을 재현하는 주 시나리오다. 아래 API 직접 Ramp는 한 endpoint를
반복 호출해 Controller·서비스·SQL 병목을 분리하는 **보조 시나리오**다. 모든 읽기 시나리오는 API 하나를
한 번만 호출하므로 `*_RPS` 값은 목표 HTTP RPS와 같다.

```bash
# API-LT-01: 내 여행 목록
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/my-travel-plans-ramping-arrival.js

# API-LT-02: 내 사용자·마이페이지 정보
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/user-me-ramping-arrival.js

# API-LT-03: 알림 목록
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/notifications-ramping-arrival.js

# API-LT-04: 알림 설정 조회
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/notification-settings-ramping-arrival.js

# API-LT-05: 여행 ID 기준 생성 상태 조회
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/api/travel-plan-status-ramping-arrival.js

# API-LT-06: Staging loadtest 계정의 닉네임을 계속 다른 값으로 변경
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
./scripts/test/run-k6.sh scenarios/api/nickname-ramping-arrival.js
```

| API 직접 Ramp | endpoint | 환경변수 접두사 | 기본 RPS 단계 |
| --- | --- | --- | --- |
| API-LT-01 | `GET /api/travel-plans/me` | `MY_TRAVELS_RAMP` | 1 → 10 → 30 → 50 → 1 |
| API-LT-02 | `GET /api/users/me` | `USER_ME_RAMP` | 1 → 10 → 30 → 50 → 1 |
| API-LT-03 | `GET /api/notifications` | `NOTIFICATIONS_RAMP` | 1 → 10 → 30 → 50 → 1 |
| API-LT-04 | `GET /api/notification-settings` | `NOTIFICATION_SETTINGS_RAMP` | 1 → 10 → 30 → 50 → 1 |
| API-LT-05 | `GET /api/travel-plans/{travelPlanId}/status` | `TRAVEL_PLAN_STATUS_RAMP` | 1 → 10 → 30 → 50 → 1 |
| API-LT-06 | `PATCH /api/users/me/nickname` | `NICKNAME_RAMP` | 1 → 10 → 30 → 50 → 1 |

각 접두사에 아래 suffix를 붙여 독립 조절한다. 기본 stage 시간은 `30s → 30s → 30s → 2m → 30s`이며,
기본 VU 풀은 `PRE_ALLOCATED_VUS=10`, `MAX_VUS=100`이다.

| suffix | 의미 |
| --- | --- |
| `_START_RPS`, `_STAGE_1_RPS`, `_STAGE_2_RPS`, `_PEAK_RPS` | 시작·중간 2단계·피크 목표 HTTP RPS |
| `_STAGE_1_DURATION`, `_STAGE_2_DURATION`, `_UP_DURATION`, `_PEAK_DURATION`, `_DOWN_DURATION` | 각 Ramp stage 시간 |
| `_PRE_ALLOCATED_VUS`, `_MAX_VUS` | k6가 요청률을 맞추기 위해 사용할 VU 풀 |

예를 들어 내 여행 목록을 `5 → 25 → 75 → 100 → 5 RPS`로 짧게 검증하려면 다음과 같이 실행한다.

```bash
CONFIRM_STAGING=true \
MY_TRAVELS_RAMP_START_RPS=5 \
MY_TRAVELS_RAMP_STAGE_1_RPS=25 \
MY_TRAVELS_RAMP_STAGE_2_RPS=75 \
MY_TRAVELS_RAMP_PEAK_RPS=100 \
MY_TRAVELS_RAMP_STAGE_1_DURATION=10s \
MY_TRAVELS_RAMP_STAGE_2_DURATION=10s \
MY_TRAVELS_RAMP_UP_DURATION=10s \
MY_TRAVELS_RAMP_PEAK_DURATION=30s \
MY_TRAVELS_RAMP_DOWN_DURATION=10s \
MY_TRAVELS_RAMP_PRE_ALLOCATED_VUS=10 \
MY_TRAVELS_RAMP_MAX_VUS=100 \
./scripts/test/run-k6.sh scenarios/api/my-travel-plans-ramping-arrival.js
```

API-LT-06은 VU별로 `u001 ↔ u001t`처럼 4자·5자 유효 닉네임을 매 iteration마다 토글하므로 원복 없이
반복 실행할 수 있다. 단, 실제 DB 변경을 재현하므로 `ALLOW_WRITE_TESTS=true`가 필요하며,
`NICKNAME_RAMP_MAX_VUS`는 현재 Staging 전용 사용자 150명을 넘길 수 없다. API-LT-03은 P-01 생성 알림이 누적될수록 응답 본문도
커질 수 있다. API-LT-04는 알림 설정 레코드가 전혀 없는 사용자에서 최초 조회 시 기본 설정을 만들 수 있으므로,
첫 실행은 Smoke로 확인한 뒤 부하를 올린다.

### 한 줄 고정 RPS 실행

`run-rps.sh`는 기존 시나리오의 환경변수 단위를 바꾸지 않고, 입력한 HTTP RPS를 내부 단위로 변환한다.
따라서 기존 `flow/min` 명령과 결과 비교 호환성을 유지하면서 짧은 명령으로 고정 처리량을 실행할 수 있다.

```bash
# LT-01 일정 조회: 50 HTTP RPS, 5분
CONFIRM_STAGING=true ./scripts/test/run-rps.sh itinerary 50 5m

# LT-05 생성 상태 폴링: 50 HTTP RPS, 5분
CONFIRM_STAGING=true ./scripts/test/run-rps.sh generation-polling 50 5m

# API 직접 부하: 내 여행 목록 100 RPS, 5분
CONFIRM_STAGING=true ./scripts/test/run-rps.sh my-travels 100 5m

# API 직접 부하: 여행 상태 조회 100 RPS, 5분
CONFIRM_STAGING=true ./scripts/test/run-rps.sh travel-plan-status 100 5m

# API 직접 쓰기: 닉네임 토글 50 RPS, 5분
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
./scripts/test/run-rps.sh nickname 50 5m
```

```text
Usage: CONFIRM_STAGING=true ./scripts/test/run-rps.sh <target> <http-rps> [duration]
```

| target | 실행 대상 | RPS 변환 |
| --- | --- | --- |
| `itinerary` | LT-01 `upcoming → recent → itinerary` | HTTP RPS × 20 = flow/min (흐름당 HTTP 3개) |
| `generation-polling` | LT-05 Job 상태 조회 | HTTP RPS × 60 = req/min |
| `my-travels` | `GET /api/travel-plans/me` | 입력값 그대로 HTTP RPS |
| `user-me` | `GET /api/users/me` | 입력값 그대로 HTTP RPS |
| `notifications` | `GET /api/notifications` | 입력값 그대로 HTTP RPS |
| `notification-settings` | `GET /api/notification-settings` | 입력값 그대로 HTTP RPS |
| `travel-plan-status` | `GET /api/travel-plans/{travelPlanId}/status` | 입력값 그대로 HTTP RPS |
| `nickname` | `PATCH /api/users/me/nickname` | 입력값 그대로 HTTP RPS, `ALLOW_WRITE_TESTS=true` 필요 |

RPS는 양의 정수만 지원한다. 기본 VU 풀은 RPS에 따라 자동 계산되며, 응답시간 증가로
`dropped_iterations`가 생기면 아래 환경변수로 k6 부하 발생기 VU 풀만 조정한다.

```bash
CONFIRM_STAGING=true \
LOADTEST_RPS_PRE_ALLOCATED_VUS=100 \
LOADTEST_RPS_MAX_VUS=300 \
./scripts/test/run-rps.sh itinerary 300 5m
```

P-01은 **생성 시작률**, P-02는 **읽기·완료·SSE 혼합**, SSE는 **동시 연결 수**가 핵심이므로 단일 RPS
명령으로 축약하지 않는다. 기존 P-01/P-02/SSE 명령을 그대로 사용한다.

### LT-01: 일정 조회 (고정 / 여행 당일 Ramp)

```bash
# 고정 처리량 Baseline: 13 flow/min, 5분
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/itinerary/constant-arrival.js

# Ramp: 낮음 → 중간 → 피크 → 유지 → 낮음
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/itinerary/ramping-arrival.js

# Ramp의 짧은 Staging Smoke 예시
CONFIRM_STAGING=true \
ITINERARY_RAMP_START_RATE_PER_MINUTE=1 \
ITINERARY_RAMP_STAGE_1_RATE_PER_MINUTE=2 \
ITINERARY_RAMP_STAGE_2_RATE_PER_MINUTE=3 \
ITINERARY_RAMP_PEAK_RATE_PER_MINUTE=4 \
ITINERARY_RAMP_STAGE_1_DURATION=10s \
ITINERARY_RAMP_STAGE_2_DURATION=10s \
ITINERARY_RAMP_UP_DURATION=10s \
ITINERARY_RAMP_PEAK_DURATION=20s \
ITINERARY_RAMP_DOWN_DURATION=10s \
ITINERARY_RAMP_PRE_ALLOCATED_VUS=2 \
ITINERARY_RAMP_MAX_VUS=10 \
./scripts/test/run-k6.sh scenarios/itinerary/ramping-arrival.js
```

| executor / 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `constant-arrival-rate`, `ITINERARY_FLOW_RATE_PER_MINUTE` | `13` flow/min | LT-01 한 flow는 `upcoming → recent → itinerary`의 **HTTP 3개**다. |
| `ramping-arrival-rate`, `ITINERARY_RAMP_START_RATE_PER_MINUTE` | `13` flow/min | Ramp 시작·종료 flow/min이다. |
| `ITINERARY_RAMP_STAGE_1/2_RATE_PER_MINUTE` | `200` / `500` | 중간 목표 flow/min이다. |
| `ITINERARY_RAMP_PEAK_RATE_PER_MINUTE` | `1000` | 피크 flow/min, HTTP 약 50 RPS다. |
| `ITINERARY_RAMP_*_DURATION` | 1m / 1m / 1m / 5m / 1m | stage1, stage2, 상승, 피크 유지, 감소 시간이다. |
| `*_PRE_ALLOCATED_VUS`, `*_MAX_VUS` | 시나리오별 상이 | 목표 요청률을 처리하기 위한 k6 VU 풀이다. RPS 자체는 rate가 결정한다. |

`목표 flow/min = 목표 HTTP RPS × 20` 이다. 예를 들어 `1,000 flow/min × 3 HTTP ÷ 60초 = 약 50 RPS`다.

### LT-02: 완료·재조회와 Toggle

```bash
# 실제 사용자 흐름(false → true)만 성능 측정 후 수동 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-k6.sh scenarios/completion/constant-arrival.js

CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/reset-completions.sh \
--summary results/<RUN-ID>/completion-constant-arrival-summary.json

# 완료 처리 Ramp를 실행하고 결과 summary 기준으로 자동 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-completion-with-reset.sh scenarios/completion/ramping-arrival.js

# 현재 상태를 읽어 반대값으로 바꾸는 양방향 Toggle + 자동 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
COMPLETION_TOGGLE_VUS=10 \
COMPLETION_TOGGLE_ITERATIONS=1 \
./scripts/test/run-completion-with-reset.sh scenarios/completion/toggle.js
```

| executor / 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `constant-arrival-rate`, `COMPLETION_FLOW_RATE_PER_MINUTE` | `1` flow/min | 실제 LT-02는 `false → true PATCH → itinerary GET`이다. |
| `ramping-arrival-rate`, `COMPLETION_RAMP_*` | 1 → 2 → 5 → 10 → 1 flow/min | 낮음 → 중간 → 피크 → 유지 → 감소. 시간은 `COMPLETION_RAMP_*_DURATION`으로 설정한다. |
| `per-vu-iterations`, `COMPLETION_TOGGLE_VUS`, `COMPLETION_TOGGLE_ITERATIONS` | 1 / 1 | Toggle은 itinerary에서 현재 상태를 읽고 반대값 PATCH 후 재조회한다. |
| `completion_attempts` | summary metric | 실제 false→true 항목 배정 수다. 첫 N개 `test-ids.json` 항목과 대응한다. |
| `completion_toggle_attempts`, `completion_false_to_true`, `completion_true_to_false` | summary metric | Toggle 배정 수와 각 실제 상태 전환 성공 수다. |

완료 관련 한 실행은 고유 `itineraryItemId` 150개까지만 사용한다. 151번째 배정은 재사용하지 않고 명확한
오류로 종료한다. 자동 복구는 performance log와 `completion-reset.log`를 분리해 기록하며, summary가 없으면
안전 fallback으로 모든 테스트 항목을 `false`로 복구한다.

### LT-05와 LT-06

```bash
# LT-05: 준비된 Job 상태 폴링 (새 여행 생성 없음)
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/generation/polling.js

# LT-06 최초 실행 전 한 번: SSE 지원 k6 바이너리 생성
./scripts/test/setup-k6-sse.sh

# LT-06: SSE handshake Smoke (연결 1회, `connected` 이벤트와 HTTP 200 확인)
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/sse/handshake-smoke.js

# LT-06: 고정 SSE 연결 유지
CONFIRM_STAGING=true \
SSE_CONNECTIONS=10 \
SSE_DURATION=5m \
./scripts/test/run-k6.sh scenarios/sse/constant-vus.js

# LT-06: 1 → 10 → 30 → 70 연결 Ramp
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/sse/ramping-vus.js
```

| executor / 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| LT-05 `constant-arrival-rate`, `GENERATION_POLL_RATE_PER_MINUTE` | 30 req/min | 요청 1개 flow이므로 `req/min ÷ 60 = RPS`다. |
| LT-06 handshake Smoke, `per-vu-iterations` | 1 VU × 1회 | `open`과 초기 `connected` 이벤트를 받은 뒤 연결을 닫고 HTTP 200을 검증한다. |
| LT-06 `constant-vus`, `SSE_CONNECTIONS`, `SSE_DURATION` | 1 / 1m | VU 1개 = SSE 연결 1개다. |
| LT-06 `ramping-vus`, `SSE_RAMP_STAGE_1/2/3_VUS`, `SSE_RAMP_PEAK_VUS` | 1 / 10 / 30 / 70 | 연결 수와 stage 시간은 `SSE_RAMP_*_DURATION`으로 조절한다. 최대 150명이다. |
| SSE metric | summary | 아래 SSE 지표를 함께 확인한다. |

LT-06과 P-02의 SSE executor는 `k6/x/sse`를 사용하므로 **SSE 지원 custom k6 바이너리**가 필요하다.
일반 `k6`의 자동 확장 해석은 현재 `k6/x/sse` 의존성을 빌드하지 못하므로 사용하지 않는다. 최초 한 번 아래
스크립트로 `.bin/k6-sse`를 만든다. 스크립트는 `xk6-sse v0.1.11`, `k6 v1.1.0`을 고정해 빌드한다.

```bash
./scripts/test/setup-k6-sse.sh
```

이 스크립트는 로컬 `xk6` 또는 Docker를 사용한다. 로컬 빌드에는 Go와 `xk6`가, Docker 빌드에는 실행 중인
Docker daemon이 필요하다. 최초 빌드에서는 Go 모듈 또는 Docker 이미지 다운로드를 위해 네트워크 연결이 필요할 수
있다. `run-k6.sh`는 `K6_BIN`이 지정되면 그것을 먼저 사용하고, 없으면 `.bin/k6-sse`, 마지막으로
일반 `k6`를 사용한다. `.bin/k6-sse`가 생성된 뒤에는 읽기·쓰기·SSE 모두 같은 k6 버전으로 실행해
결과 비교 조건을 맞춘다. 고부하 실행 전에는 SSE 바이너리로 시나리오 로딩을 확인한다.

```bash
set -a
source .env
set +a

CONFIRM_STAGING=true \
./.bin/k6-sse inspect --include-system-env-vars \
scenarios/sse/handshake-smoke.js
```

| metric | 의미 | 적용 범위 |
| --- | --- | --- |
| `sse_connection_attempts` | SSE 구독 요청을 시작한 횟수 | handshake Smoke, 연결 유지 |
| `sse_connection_opened` | SSE 연결이 열렸다는 `open` 콜백 수 | handshake Smoke, 연결 유지 |
| `sse_connected_events` | Backend가 보낸 초기 `connected` 이벤트 수 | handshake Smoke, 연결 유지 |
| `sse_events_received` | 초기 이벤트를 포함해 수신한 전체 SSE 이벤트 수 | handshake Smoke, 연결 유지 |
| `sse_connection_errors` | SSE client 오류 콜백 수 | handshake Smoke, 연결 유지 |
| `sse_handshake_200` | 연결을 명시적으로 닫은 뒤 확인한 HTTP 200 수 | handshake Smoke |

브라우저 수준 자동 재연결, `Last-Event-ID`, 장시간 이벤트 전달 보장은 별도로 측정하지 않는다. 재구독은
handshake Smoke를 다시 실행해 확인한다.

### P-01 생성 증가와 P-02 여행 당일 스파이크

```bash
# P-01 고정 생성 시작률
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
./scripts/test/run-k6.sh scenarios/p01/constant-arrival.js

# P-01 생성 시작률 Ramp
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
./scripts/test/run-k6.sh scenarios/p01/ramping-arrival.js

# P-01 동시 생성 사용자(VU) Ramp: 각 VU는 생성 완료 뒤 다음 여행 생성을 반복
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
./scripts/test/run-k6.sh scenarios/p01/ramping-vus.js

# P-01 알림 전달 검증: 사용자별 SSE 연결 → 같은 사용자의 여행 생성 → notification 수신
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_AI_MOCK=true \
P01_NOTIFICATION_USERS=1 \
./scripts/test/run-k6.sh scenarios/p01/notification-delivery.js

# 위 실행의 결과 파일을 대상으로 1:1 알림 전달을 검증한다.
node scripts/test/assert-k6-summary.mjs \
  --summary results/<run-id>/p01-notification-delivery-summary.json \
  --mode p01-notification \
  --expected-notifications 1

# 기존 P-02 고정 혼합 부하 + 자동 완료 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-completion-with-reset.sh scenarios/p02/constant-mix.js

# 여행 당일 Ramp: LT-01 Ramp + LT-02 Ramp + 고정 SSE 동시 실행, 자동 복구
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/test/run-completion-with-reset.sh scenarios/p02/ramping-spike.js
```

| 프로파일 / executor | 기본값 | 설명 |
| --- | --- | --- |
| P-01 `constant-arrival-rate`, `P01_CREATION_RATE_PER_HOUR` | 6 생성/시간 | 일정한 생성 시작률의 Backend·DB 처리량을 본다. VU는 고정 사용자를 순차 재사용한다. |
| P-01 `ramping-arrival-rate`, `P01_RAMP_*_RATE_PER_HOUR` | 6 → 12 → 30 → 60 → 6 | 여행일 전 증가·집중 패턴을 본다. stage 시간은 `P01_RAMP_*_DURATION`이고 VU는 고정 사용자를 순차 재사용한다. |
| P-01 `ramping-vus`, `P01_RAMP_VU_*` | 1 → 3 → 10 → 0 VU | VU별 고정 사용자가 `지역 → 생성 → 폴링 → 일정` 전체 흐름을 완료할 때마다 반복한다. 최대 동시 VU는 150명이다. |
| P-01 알림 전달, `per-vu-iterations` 2개 | 사용자 1명 × SSE 1회·생성 1회 | SSE 연결을 먼저 열고 `P01_NOTIFICATION_SSE_READY_DELAY`(기본 5초) 뒤 같은 사용자가 여행을 생성한다. `P01_NOTIFICATION_USERS`는 최대 150명이다. |
| P-02 고정 | 13 읽기 flow/min, 5 완료 flow/min, SSE 10 | 기존 혼합 프로파일이다. `P02_DURATION`이 공통 시간이다. |
| P-02 Ramp | 읽기 13→26→52→100→13, 완료 1→2→5→10→1, SSE 10 | 여행 당일 스파이크용이다. 읽기 `P02_ITINERARY_RAMP_*_DURATION`과 완료 `P02_COMPLETION_RAMP_*_DURATION`은 독립 설정하되 총합이 같아야 하며, SSE 시간은 그 합계로 자동 계산된다. |

P-01은 실행마다 새 여행·생성 Job·일정 데이터를 Staging DB에 남긴다. VU는 한 사용자에 고정되지만, 이전
생성 전체 흐름이 끝난 뒤 같은 사용자가 다음 여행을 생성할 수 있다. 따라서 150명은 총 생성 수가 아니라
**최대 동시 VU 수** 한도다. 반드시 AI Mock 라우팅을 배포에서 확인하고 `CONFIRM_AI_MOCK=true`를 넣는다.
P-01 생성 데이터는 `loadtest-` 사용자와 실행 ID를 기준으로 별도 정리한다.

#### P-01 API별 병목 집계

P-01의 세 생성 부하 방식과 알림 전달 시나리오는 공통 생성 흐름에서 아래 지표를 자동 수집한다.
기존 실행 명령은 그대로 사용한다. 응답시간·실패율 threshold나 자동 감속은 추가하지 않는다.

| 지표 접두사 | 측정 API | 기대 상태 |
| --- | --- | --- |
| `p01_regions` | `GET /api/regions` | 200 |
| `p01_travel_plan_create` | `POST /api/travel-plans` | 202 |
| `p01_generation_status` | `GET /api/ai-generation-jobs/{jobId}` | 200 |
| `p01_itinerary_after_generation` | `GET /api/travel-plans/{id}/itinerary` | 200 |

| 접두사 뒤 suffix | summary 값 | 의미 |
| --- | --- | --- |
| `_requests` | `count`, `rate` | 응답이 반환돼 집계된 요청 수·실행 전체 시간 기준 초당 수 |
| `_errors` | `count` | 기대 HTTP 상태와 다른 응답 수. 네트워크 실패의 status 0도 포함 |
| `_failed` | `value` | 기대 HTTP 상태 불일치 비율 (0~1). 응답 본문·Job 결과 실패는 기존 checks/생성 지표로 별도 확인 |
| `_duration` | `avg`, `p(95)`, `max` 등 | HTTP 요청 소요 시간. JSON 단위는 ms |
| `_waiting` | `avg`, `p(95)`, `max` 등 | 응답 첫 바이트 대기 시간. JSON 단위는 ms |

예를 들어 `p01_travel_plan_create_duration.p(95)`와 `p01_generation_status_duration.p(95)`를
비교하면 생성 접수와 폴링 중 어디가 느린지 구분할 수 있다. 기존 전체 HTTP 지표도 유지한다.
측정은 기존 HTTP 응답 직후에 수행하므로 추가 API 요청은 발생하지 않는다. 중단 당시 반환되지 않은
요청은 집계되지 않을 수 있고, 호출하지 못한 API의 지표는 summary에 없을 수 있다.
HTTP 200으로 반환된 Job의 `FAILED` 상태는 `_errors`가 아니라 `p01_generation_failed`에 집계된다.
기존 실행 summary에는 이 집계를 소급 추가할 수 없으며 다음 실행부터 저장된다.

로컬 집계 검증 (합성 성공·503·네트워크 실패 응답만 사용, HTTP 요청 없음):

```bash
./.bin/k6-sse run \
  --summary-export /tmp/p01-api-metrics-summary.json \
  scripts/test/p01-api-metrics-smoke.js
```

P-01 알림 전달 시나리오는 생성 완료에 따라 Backend가 같은 사용자의 SSE로 보내는 `notification`
이벤트를 확인한다. `p01_notification_waits`, `p01_generation_completed`,
`p01_notification_events_received`가 모두 사용자 수와 같고 `sse_connection_errors=0`이어야 한다.
SSE 연결 준비 시간은 `P01_NOTIFICATION_SSE_READY_DELAY`, 이벤트 대기 timeout은
`P01_NOTIFICATION_WAIT_TIMEOUT`(기본 6분)으로 조절한다. 실제 AI가 아닌 Staging AI Mock에서만 실행한다.

401/403이 발생하면 요청률을 높이지 말고 JWT Secret·토큰 TTL·Staging 접근 권한을 먼저 수정한다.


## 현재 진행 상태 (2026-10-01)

- 2026-10-01 18:58 KST에 JWT 재발급 후 인증 preflight HTTP 200과 읽기 Smoke를 통과했다.
  HTTP 요청 5개와 check 15/15가 모두 성공했고, 오류율 0%, p95 28.99ms였다.
- Staging DB에 `loadtest-001`~`loadtest-150` 사용자 150명을 생성했다.
- 사용자별 JWT, 미래 여행의 `travelPlanId`, `itineraryItemId`, `generationJobId`가
  `data/test-ids.json`에 준비됐다.
- 사용자별 두 번째 여행을 생성한 뒤 과거 날짜로 변경했고, 과거 여행 보유 사용자 수 150명을
  확인했다.
- 이전 기본 Smoke 결과도 HTTP 요청 5개 모두 성공, check 15/15 성공, 오류율 0%, p95 약 84.86ms였다.
- 완료 처리 Smoke는 HTTP 요청 7개 모두 성공, check 21/21 성공, 오류율 0%, p95 약 38.88ms였고
  실행 후 대상 항목을 `is_completed=false`로 복구했다.
- LT-02는 iteration마다 서로 다른 사용자를 쓰며, 실행 summary를 기준으로 사용한 항목만
  `is_completed=false`로 되돌릴 수 있다.
- 최초 데이터 구축은 완료됐다. 실제 부하 실행 전에는 만료된 JWT를 다시 발급하고 완료 상태를
  초기화한 뒤 단독 Baseline부터 시작한다.

위 상태는 현재 Staging 데이터에 대한 기록이다. Staging DB를 초기화하거나 JWT Secret을 바꾸면
아래 최초 구축 절차를 필요한 단계부터 다시 수행한다.

## 1. 핵심 원칙과 최종 데이터 형태

### 안전 원칙

- 모든 명령의 대상은 별도 Staging API와 Staging DB여야 한다.
- `load-test/.env`에는 `BASE_URL`과 리소스 식별자만 두고 JWT Secret은 저장하지 않는다.
- AI 생성 고부하는 AI Mock을 사용한다. `CONFIRM_AI_MOCK=true`는 확인 플래그일 뿐 Mock 라우팅을
  자동으로 구성하지 않는다.
- 현재 Backend의 카카오 서버 호출을 막으려면 Staging Task의 `KAKAO_REST_API_KEY`를 빈 값으로
  두고 재배포한다. 브라우저의 Kakao Maps JavaScript SDK는 Backend k6 범위가 아니다.
- `data/test-ids.json`, `data/create-request.json`, `data/loadtest-user-ids.txt`, `results/`는 Git에서
  제외된다. 토큰·JWT Secret·실제 AWS 식별자를 커밋하지 않는다.

### 사용자 한 명당 준비할 데이터

| 데이터 | 용도 | `test-ids.json` 기록 여부 |
| --- | --- | --- |
| `users` 행 1개 | JWT의 `sub`와 인증 사용자 | `userId` 기록 |
| 생성 완료 상태의 미래 여행 1개 | `upcoming`, 일정 조회, 완료 처리 | 여행·항목·Job ID 기록 |
| 미래 여행의 미완료 일정 항목 1개 | LT-02 완료 처리 | `itineraryItemId` 기록 |
| 미래 여행의 생성 Job 1개 | LT-05 상태 폴링 | `generationJobId` 기록 |
| 생성 완료 상태의 과거 여행 1개 | `recent` 조회 | 기록하지 않음 |
| 24시간 Access Token 1개 | 모든 API 인증 | `accessToken` 기록 |

여행은 사용자당 최소 2개다. 여기서 `COMPLETED`는 여행을 다녀왔다는 뜻이 아니라 **AI 일정 생성이
완료된 상태**다. 첫 번째 여행은 미래 날짜로 유지하고, 두 번째 여행만 DB에서 과거 날짜로 바꾼다.

`test-ids.json`의 한 항목은 다음 의미를 가진다.

```json
{
  "userId": 1,
  "accessToken": "<사용자 1의 JWT>",
  "travelPlanId": 1,
  "itineraryItemId": 1,
  "generationJobId": 1
}
```

세 리소스 ID는 모두 **첫 번째 미래 여행**에 속해야 한다. 두 번째 과거 여행 ID를 넣으면 LT-02와
LT-05가 서로 다른 여행을 보게 되므로 넣지 않는다.

## 2. 제공 시나리오와 기본 요청량

```
scenarios/
├── smoke.js
├── itinerary/   # LT-01: flow.js + constant-arrival.js + ramping-arrival.js
├── completion/  # LT-02: flow.js + constant-arrival.js + ramping-arrival.js + toggle.js
├── api/         # API 직접 Ramp + 고정 RPS: 5개 읽기 endpoint + 닉네임 변경
├── generation/  # LT-05: polling.js
├── sse/         # LT-06: flow.js + handshake-smoke.js + constant-vus.js + ramping-vus.js
├── p01/         # LT-04 전체 생성 3가지 executor + notification-delivery.js
└── p02/         # 고정 혼합과 여행 당일 Ramp 혼합
```

| 파일 | 설계 ID | executor | 기본 설정 |
| --- | --- | --- | --- |
| `scenarios/smoke.js` | Smoke | shared iterations | 1 VU, 1 iteration |
| `itinerary/constant-arrival.js` | LT-01 | constant-arrival-rate | 13 flow/min, 5m |
| `itinerary/ramping-arrival.js` | LT-01 | ramping-arrival-rate | 13 → 200 → 500 → 1000 → 13 flow/min |
| `completion/constant-arrival.js` | LT-02 | constant-arrival-rate | 1 flow/min, 5m |
| `completion/ramping-arrival.js` | LT-02 | ramping-arrival-rate | 1 → 2 → 5 → 10 → 1 flow/min |
| `completion/toggle.js` | LT-02 검증 | per-vu-iterations | 1 VU × 1회 |
| `api/constant-arrival.js` | API 직접 고정 RPS | constant-arrival-rate | `API_TARGET`의 단일 endpoint, `API_RPS` req/s |
| `api/my-travel-plans-ramping-arrival.js` | API-LT-01 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS |
| `api/user-me-ramping-arrival.js` | API-LT-02 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS |
| `api/notifications-ramping-arrival.js` | API-LT-03 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS |
| `api/notification-settings-ramping-arrival.js` | API-LT-04 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS |
| `api/travel-plan-status-ramping-arrival.js` | API-LT-05 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS |
| `api/nickname-ramping-arrival.js` | API-LT-06 | ramping-arrival-rate | 1 → 10 → 30 → 50 → 1 RPS, write guard |
| `generation/polling.js` | LT-05 | constant-arrival-rate | 30 req/min, 5m |
| `sse/handshake-smoke.js` | LT-06 검증 | per-vu-iterations | 1 VU × 1회, `connected` 이벤트·HTTP 200 확인 |
| `sse/constant-vus.js` | LT-06 | constant-vus | 1 연결, 1m |
| `sse/ramping-vus.js` | LT-06 | ramping-vus | 1 → 10 → 30 → 70 → 1 연결 |
| `p01/constant-arrival.js` | P-01/LT-04 | constant-arrival-rate | 6 생성/h, 1h; VU별 순차 반복 생성 |
| `p01/ramping-arrival.js` | P-01/LT-04 | ramping-arrival-rate | 6 → 12 → 30 → 60 → 6 생성/h; VU별 순차 반복 생성 |
| `p01/ramping-vus.js` | P-01/LT-04 | ramping-vus | 1 → 3 → 10 → 0 VU, VU별 순차 반복 생성 |
| `p01/notification-delivery.js` | P-01 알림 전달 | per-vu-iterations × 2 | 사용자별 SSE 1회와 같은 사용자 생성 1회를 짝지어 `notification` 수신을 검증 |
| `p02/constant-mix.js` | P-02 | CAR + CAR + CVU | 읽기 13, 완료 5 flow/min, SSE 10 |
| `p02/ramping-spike.js` | P-02 | RAR + RAR + CVU | 읽기·완료 Ramp + SSE 10 |

도착률 executor는 처리 한계를 넘으면 `dropped_iterations`가 생길 수 있다. 이는 자동 감속 신호가 아니라,
해당 목표율에서 처리하지 못한 흐름이라는 한계 분석 결과다. 실제 `http_reqs`, `iterations`,
`dropped_iterations`, p95, 5xx와 서버 CPU·메모리를 같은 시간대에 기록한다.

## 3. 최초 1회 데이터 구축

아래 순서를 바꾸지 않는다. **사용자 1명 전체 흐름과 Smoke가 성공한 뒤** 나머지 사용자를 만든다.

### 3.1 로컬 도구와 `.env` 준비

```bash
cd load-test
node --version
k6 version
cp .env.example .env
```

`.env`의 최소 설정:

```dotenv
BASE_URL=https://<staging-api-domain>
CONFIRM_STAGING=true
ACCESS_TOKEN=
TEST_RUN_ID=
```

Staging Task의 주요 설정도 먼저 확인한다.

```text
AUDIGO_JWT_SECRET=<운영과 다른 Staging 전용 Secret>
AUDIGO_ACCESS_TOKEN_TTL_SECONDS=86400
AUDIGO_AI_BASE_URL=<AI Mock 주소>
AUDIGO_AI_SSE_PATH=<Mock이 제공하는 SSE 경로>
KAKAO_REST_API_KEY=
```

`AUDIGO_AI_BASE_URL`과 `AUDIGO_AI_SSE_PATH`의 실제 조합으로 Backend가 Mock 응답을 받는지 확인한 후
다음 단계로 간다.

```bash
set -a; source .env; set +a
curl --fail --silent --show-error --max-time 10 "$BASE_URL/health"
```

Health Check가 실패하면 시딩이나 k6를 실행하지 않는다. 보안 그룹, 80/443 리스너, Nginx upstream,
ECS Task 상태와 Staging DNS를 먼저 고친다.

### 3.2 EC2의 MySQL 컨테이너에 테스트 사용자 150명 생성

로컬에서 Staging EC2에 접속한다.

```bash
ssh -i "<STAGING_SSH_KEY.pem>" ec2-user@<STAGING_EC2_HOST>
sudo docker ps --format 'table {{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}'
sudo docker exec -it <MYSQL_CONTAINER> mysql -uroot -p
```

MySQL에서 Staging DB를 선택하고 사용자를 생성한다. 아래 프로시저는 같은 닉네임이 이미 있으면
건너뛰므로 재실행할 수 있다.

```sql
USE audigo;

DROP PROCEDURE IF EXISTS seed_loadtest_users;
DELIMITER $$
CREATE PROCEDURE seed_loadtest_users()
BEGIN
    DECLARE n INT DEFAULT 1;

    WHILE n <= 150 DO
        IF NOT EXISTS (
            SELECT 1
            FROM users
            WHERE nickname = CONCAT('loadtest-', LPAD(n, 3, '0'))
        ) THEN
            INSERT INTO users (
                nickname,
                profile_image_url,
                status,
                created_at,
                updated_at,
                deleted_at
            ) VALUES (
                CONCAT('loadtest-', LPAD(n, 3, '0')),
                NULL,
                'ACTIVE',
                NOW(6),
                NOW(6),
                NULL
            );
        END IF;

        SET n = n + 1;
    END WHILE;
END$$
DELIMITER ;

CALL seed_loadtest_users();
DROP PROCEDURE seed_loadtest_users;
```

정확히 150명의 활성 사용자가 있어야 한다.

```sql
SELECT COUNT(*) AS loadtest_user_count
FROM users
WHERE nickname REGEXP '^loadtest-[0-9]{3}$'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

SELECT id, nickname
FROM users
WHERE nickname REGEXP '^loadtest-[0-9]{3}$'
ORDER BY CAST(RIGHT(nickname, 3) AS UNSIGNED);
```

`nickname`의 숫자와 `users.id`는 같은 값이라고 가정하지 않는다. 이후 모든 JWT와 리소스 매핑은
조회된 실제 `users.id`를 사용한다.

### 3.3 DB 사용자 ID를 로컬 `test-ids.json`으로 옮기기

먼저 MySQL monitor에서 `exit`해 EC2 셸로 돌아온다. 공식 MySQL 컨테이너처럼
`MYSQL_ROOT_PASSWORD`가 컨테이너 환경변수로 설정돼 있다면 다음 명령으로 ID만 추출한다.

```bash
sudo docker exec -i <MYSQL_CONTAINER> sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names audigo' \
  > /tmp/loadtest-user-ids.txt <<'SQL'
SELECT id
FROM users
WHERE nickname REGEXP '^loadtest-[0-9]{3}$'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
ORDER BY CAST(RIGHT(nickname, 3) AS UNSIGNED);
SQL

wc -l /tmp/loadtest-user-ids.txt
```

결과가 `150`이어야 한다. 컨테이너에 `MYSQL_ROOT_PASSWORD`가 없다면 동일한 SELECT를 사용할 수 있는
Staging DB 계정으로 실행하되 비밀번호를 명령행이나 Git에 남기지 않는다.

로컬 Mac의 `load-test/` 디렉터리에서 파일을 가져오고 JSON을 초기화한다.

```bash
scp -i "<STAGING_SSH_KEY.pem>" \
  ec2-user@<STAGING_EC2_HOST>:/tmp/loadtest-user-ids.txt \
  data/loadtest-user-ids.txt

wc -l data/loadtest-user-ids.txt
node scripts/initData/initialize-test-ids.mjs
```

`initialize-test-ids.mjs`는 ID가 정확히 150개이고 중복이 없는지 확인한 뒤 다음 초기값을 만든다.

```json
{
  "userId": 1,
  "accessToken": "",
  "travelPlanId": null,
  "itineraryItemId": null,
  "generationJobId": null
}
```

`data/test-ids.example.json`은 구조 참고용이다. 실제 파일은 반드시 DB에서 추출한 ID로 만든다.
이미 리소스 ID가 들어간 `test-ids.json`이 있으면 초기화 도구는 덮어쓰지 않는다. 전체 데이터를
처음부터 폐기하고 재구축할 때만 `--force`를 사용한다.

```bash
# 기존 토큰과 모든 리소스 ID를 지운다는 뜻이므로 최초 재구축에서만 사용
node scripts/initData/initialize-test-ids.mjs --force
```

### 3.4 사용자별 JWT 150개 발급

JWT는 Staging Backend에 로그인 요청을 보내 발급받는 것이 아니라, Staging의
`AUDIGO_JWT_SECRET`과 같은 Secret으로 로컬에서 HS256 서명한다. Payload는 Backend 계약에 맞게
`sub`, `iat`, `exp`, `token_type: access`를 갖는다.

Secret을 `.env`나 명령행 인수에 쓰지 않는다.

```bash
read -rs STAGING_JWT_SECRET && export STAGING_JWT_SECRET
printf '\n'
node scripts/initData/generate-test-tokens.mjs --ttl 86400
unset STAGING_JWT_SECRET
```

성공하면 150명의 `accessToken`만 채우고 파일 권한을 `0600`으로 설정한다. Secret이 Backend와
다르면 모든 요청이 401이고, Secret을 교체하면 기존 토큰은 즉시 무효화된다. TTL이 24시간이므로
**실제 부하테스트 직전에 같은 명령으로 다시 발급**한다.

토큰 자체를 출력하지 않고 개수만 확인한다.

```bash
node <<'NODE'
const fs = require('fs');
const { users } = JSON.parse(fs.readFileSync('data/test-ids.json', 'utf8'));
const valid = users.filter((user) =>
  typeof user.accessToken === 'string' && user.accessToken.split('.').length === 3
);
console.log(`JWT present: ${valid.length}/${users.length}`);
if (valid.length !== users.length) process.exit(1);
NODE
```

### 3.5 여행 생성 요청 준비

```bash
cp data/create-request.example.json data/create-request.json
```

`data/create-request.json`에서 다음을 확인한다.

- `region_id`: Staging DB에 실제로 존재하는 지역 ID
- `arrival_datetime`, `departure_datetime`: 현재보다 충분히 먼 미래
- `transport_type`: Backend·DB만 측정하려면 `CAR` 또는 `WALK` 권장
- AI Mock이 반환할 일정에 미완료 일정 항목이 최소 1개 존재

실제 파일은 Git에서 제외된다.

### 3.6 첫 사용자: 미래 여행 생성 및 ID 자동 기록

DB ID가 1부터 시작한다고 가정하지 않고 JSON의 첫 사용자 ID를 읽는다.

```bash
FIRST_USER_ID="$(node -p \
  "JSON.parse(require('fs').readFileSync('data/test-ids.json', 'utf8')).users[0].userId")"
echo "FIRST_USER_ID=$FIRST_USER_ID"

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/initData/seed-test-user.sh --user-id "$FIRST_USER_ID"
```

시딩 도구의 실제 흐름은 다음과 같다.

1. 사용자 JWT로 `POST /api/travel-plans`
2. Backend가 반환한 `travel_plan_id`, `generation_job_id` 확인
3. `GET /api/ai-generation-jobs/{generationJobId}`를 완료될 때까지 폴링
4. `GET /api/travel-plans/{travelPlanId}/itinerary`
5. 첫 미완료 일정 항목 선택
6. 세 ID를 해당 사용자의 `test-ids.json` 항목에 원자적으로 기록

즉, AI에 직접 `travelPlanId`를 만들어 보내지 않는다. Backend가 먼저 ID를 만들고 AI Mock 연동을
시작한다. 성공 로그 예시는 다음과 같다.

```text
Seeded userId 1.
Recorded travelPlanId=1, generationJobId=1, itineraryItemId=1.
```

### 3.7 첫 사용자: 두 번째 여행 생성 후 과거 여행으로 변경

두 번째 여행은 `--no-record`로 생성해 첫 번째 미래 여행 ID를 덮어쓰지 않는다. 나중에 SQL을
재생성할 수 있도록 로그를 보관한다.

```bash
mkdir -p results/past-seed
set -o pipefail

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/initData/seed-test-user.sh --user-id "$FIRST_USER_ID" --no-record \
  | tee "results/past-seed/user-${FIRST_USER_ID}.log"
```

과거 날짜는 실행 시점보다 이전이어야 한다. 다음 값은 2026-09-30 기준 예시다.

```bash
PAST_ARRIVAL='2026-09-01 10:00:00'
PAST_DEPARTURE='2026-09-02 18:00:00'

node scripts/initData/generate-past-travel-sql.mjs \
  --user-id "$FIRST_USER_ID" \
  --arrival "$PAST_ARRIVAL" \
  --departure "$PAST_DEPARTURE" \
  --output results/past-seed/update-past-first-user.sql
```

생성된 SQL은 `START TRANSACTION`까지만 포함하고 `COMMIT`하지 않는다. 로컬에서 EC2 호스트로,
다시 MySQL 컨테이너로 복사한다.

```bash
# 로컬 Mac
scp -i "<STAGING_SSH_KEY.pem>" \
  results/past-seed/update-past-first-user.sql \
  ec2-user@<STAGING_EC2_HOST>:/tmp/update-past-first-user.sql

# Staging EC2
sudo docker cp /tmp/update-past-first-user.sql \
  <MYSQL_CONTAINER>:/tmp/update-past-first-user.sql
sudo docker exec -it <MYSQL_CONTAINER> mysql -uroot -p audigo
```

MySQL monitor에서 실행한다.

```sql
SOURCE /tmp/update-past-first-user.sql;
```

다음 두 조건을 모두 만족할 때만 커밋한다.

- `verified_past_travel_count = 1`
- 마지막 불일치 조회 결과가 0행

`changed_past_travel_count`는 최초 실행이면 1이고, 같은 날짜로 재실행한 경우 0일 수 있다. 중요한
값은 검증 결과다.

```sql
COMMIT;
```

검증이 다르면 다음을 실행하고 ID·날짜·Staging 대상 여부를 다시 확인한다.

```sql
ROLLBACK;
```

### 3.8 첫 사용자 읽기 Smoke

```bash
./scripts/test/run-k6.sh scenarios/smoke.js
```

기본 Smoke는 `regions`, `upcoming`, `recent`, `itinerary`, `generation status`의 GET 5개를 보낸다.
기대 결과는 5개 모두 HTTP 200, check 실패 0개다. 이 단계가 성공하기 전에는 나머지 149명을
시딩하지 않는다.

### 3.9 나머지 사용자의 미래 여행 시딩

아래 루프는 첫 사용자를 제외하고, 아직 세 리소스 ID가 없는 사용자만 순차 처리한다. AI Mock과
DB에 순간 부하를 주지 않도록 병렬 생성하지 않는다.

```bash
bash <<'BASH'
set -Eeuo pipefail

while IFS= read -r user_id; do
  echo "=== Seeding future travel for userId ${user_id} ==="
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
    ./scripts/initData/seed-test-user.sh --user-id "$user_id"
done < <(
  node -e '
    const fs = require("fs");
    const { users } = JSON.parse(fs.readFileSync("data/test-ids.json", "utf8"));
    users.slice(1)
      .filter((user) => !user.travelPlanId || !user.itineraryItemId || !user.generationJobId)
      .forEach((user) => console.log(user.userId));
  '
)
BASH
```

중간 실패 시 즉시 멈춘다. 원인을 해결한 뒤 같은 루프를 다시 실행하면 이미 완료된 사용자는
건너뛴다. 성공한 사용자에게 `--replace`를 사용하면 불필요한 새 여행이 생기므로 사용하지 않는다.

### 3.10 미래 여행 데이터 150명 검증

토큰은 출력하지 않고 누락과 중복만 확인한다.

```bash
node <<'NODE'
const fs = require('fs');
const { users } = JSON.parse(fs.readFileSync('data/test-ids.json', 'utf8'));
const fields = ['accessToken', 'travelPlanId', 'itineraryItemId', 'generationJobId'];
const missing = users.filter((user) => fields.some((field) => !user[field]));

for (const field of ['userId', 'travelPlanId', 'itineraryItemId', 'generationJobId']) {
  const values = users.map((user) => user[field]);
  if (new Set(values).size !== values.length) {
    console.error(`Duplicate ${field}`);
    process.exit(1);
  }
}

if (users.length !== 150 || missing.length) {
  console.error(`users=${users.length}, incomplete=${missing.map((user) => user.userId).join(',')}`);
  process.exit(1);
}
console.log('Validated 150 users with unique future travel/item/job IDs.');
NODE
```

### 3.11 나머지 사용자의 두 번째 여행 생성

첫 사용자를 제외한 나머지 사용자도 `--no-record`로 두 번째 여행을 만든다. 완료 결과가 들어 있는
로그가 이미 있으면 건너뛰므로 재개할 수 있다.

```bash
bash <<'BASH'
set -Eeuo pipefail
mkdir -p results/past-seed

while IFS= read -r user_id; do
  log_file="results/past-seed/user-${user_id}.log"
  if [[ -f "$log_file" ]] && grep -Eq \
    '^travelPlanId=[0-9]+, generationJobId=[0-9]+, itineraryItemId=[0-9]+\.$' \
    "$log_file"; then
    echo "=== Past seed already recorded for userId ${user_id}; skipping ==="
    continue
  fi

  echo "=== Seeding second travel for userId ${user_id} ==="
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
    ./scripts/initData/seed-test-user.sh --user-id "$user_id" --no-record \
    | tee "$log_file"
done < <(
  node -e '
    const fs = require("fs");
    const { users } = JSON.parse(fs.readFileSync("data/test-ids.json", "utf8"));
    users.slice(1).forEach((user) => console.log(user.userId));
  '
)
BASH
```

### 3.12 두 번째 여행 150개를 과거 날짜로 일괄 변경

첫 사용자 로그까지 포함해 150개 로그를 읽고, 사용자 ID와 두 번째 여행 ID의 대응표가 들어간
검증형 SQL을 생성한다.

```bash
PAST_ARRIVAL='2026-09-01 10:00:00'
PAST_DEPARTURE='2026-09-02 18:00:00'

node scripts/initData/generate-past-travel-sql.mjs \
  --arrival "$PAST_ARRIVAL" \
  --departure "$PAST_DEPARTURE"
```

기존 작업처럼 첫 사용자는 이미 과거로 바뀌었고 사용자 2~150 로그만 있는 경우에는 다음처럼
JSON 배열의 두 번째 사용자부터 SQL을 만들 수 있다.

```bash
node scripts/initData/generate-past-travel-sql.mjs \
  --from-index 2 \
  --arrival "$PAST_ARRIVAL" \
  --departure "$PAST_DEPARTURE"
```

SQL을 EC2와 MySQL 컨테이너에 복사한다.

```bash
# 로컬 Mac
scp -i "<STAGING_SSH_KEY.pem>" \
  results/past-seed/update-past-travels.sql \
  ec2-user@<STAGING_EC2_HOST>:/tmp/update-past-travels.sql

# Staging EC2
sudo docker cp /tmp/update-past-travels.sql \
  <MYSQL_CONTAINER>:/tmp/update-past-travels.sql
sudo docker exec -it <MYSQL_CONTAINER> mysql -uroot -p audigo
```

MySQL monitor:

```sql
SOURCE /tmp/update-past-travels.sql;
```

전체 150명 SQL이면 `verified_past_travel_count = 150`, `--from-index 2` SQL이면 149여야 하며 마지막
불일치 조회는 0행이어야 한다. 확인 후에만 다음을 실행한다.

```sql
COMMIT;
```

최종적으로 150명 모두 미래·과거 여행을 한 개 이상 갖는지 검증한다.

```sql
SELECT
    COUNT(DISTINCT CASE
        WHEN travel.departure_datetime >= NOW() THEN travel.user_id
    END) AS users_with_future_travel,
    COUNT(DISTINCT CASE
        WHEN travel.departure_datetime < NOW() THEN travel.user_id
    END) AS users_with_past_travel
FROM travel_plans AS travel
JOIN users AS test_user ON test_user.id = travel.user_id
WHERE test_user.nickname REGEXP '^loadtest-[0-9]{3}$'
  AND travel.status = 'COMPLETED';
```

두 값이 모두 150이면 최초 데이터 구축이 끝난다. 마지막으로 기본 Smoke를 한 번 더 실행한다.

```bash
./scripts/test/run-k6.sh scenarios/smoke.js
```

## 4. 매 부하테스트 직전 준비

최초 데이터는 재사용하고 토큰과 완료 상태만 복구한다.

1. Staging 시작 및 Health Check
2. 배포 커밋·Task CPU/메모리·JVM 옵션·DB 스키마·Mock 라우팅 확인
3. 24시간 JWT 150개 재발급
4. LT-02 대상 완료 상태를 `false`로 초기화
5. 읽기 Smoke 성공 확인
6. CloudWatch와 애플리케이션 로그의 관측 시간대 기록

```bash
cd load-test

# AWS 식별자와 권한이 .env에 설정된 경우
./scripts/start-staging.sh

set -a; source .env; set +a
curl --fail --silent --show-error --max-time 10 "$BASE_URL/health"

read -rs STAGING_JWT_SECRET && export STAGING_JWT_SECRET
printf '\n'
node scripts/initData/generate-test-tokens.mjs --ttl 86400
unset STAGING_JWT_SECRET

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh

./scripts/test/run-k6.sh scenarios/smoke.js
```

## 5. 권장 부하테스트 진행 순서

### 5.1 Smoke

```bash
./scripts/test/run-k6.sh scenarios/smoke.js
```

쓰기 Smoke가 필요하면 완료 상태를 즉시 되돌린다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true RUN_COMPLETION_SMOKE=true \
  ./scripts/test/run-k6.sh scenarios/smoke.js

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh --count 1
```

실제 AI·카카오 외부 연동 Smoke는 소수 요청으로만 수행한다. AI Mock 시딩과 혼동하지 않는다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
CONFIRM_EXTERNAL_SMOKE=true RUN_CREATION_SMOKE=true \
  ./scripts/test/run-k6.sh scenarios/smoke.js
```

### 5.2 전체 Baseline 일괄 실행

현재 구현된 Backend 시나리오를 모두 한 번씩 실행하려면 다음 명령을 사용한다. 약 16~18분이 걸리며
하나의 `results/YYYY-MM-DD-HH-mm/` 폴더에 Smoke, LT-01, LT-05, LT-02, LT-06 handshake·연결 유지 결과와 전체 로그를
저장한다.

```bash
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
  ./scripts/test/run-baseline-suite.sh
```

일괄 실행은 다음을 자동 수행한다.

1. 테스트 데이터 150명, 리소스 ID 중복, JWT subject·만료 시간 검증
2. Staging Health Check
3. 기본 읽기 Smoke
4. LT-01 일정 조회 Baseline 5분
5. LT-05 생성 상태 폴링 Baseline 5분
6. 완료 상태 전체 초기화 후 LT-02 Baseline 5분
7. LT-02에서 실제 사용한 항목만 summary 기준으로 다시 초기화
8. LT-06 SSE handshake Smoke: `open`, 초기 `connected` 이벤트, HTTP 200 검증
9. LT-06 SSE 연결 1개 Baseline 1분
10. check 실패·HTTP 실패·SSE 오류·dropped iteration 검증

중간에 LT-02가 중단되면 안전장치가 전체 완료 항목 초기화를 시도한다. 결과 폴더에는 다음 파일이
생긴다.

```text
results/YYYY-MM-DD-HH-mm/
├── suite.log
├── suite-config.txt
├── smoke-summary.json
├── itinerary-constant-arrival-summary.json
├── generation-polling-summary.json
├── completion-constant-arrival-summary.json
├── sse-handshake-smoke-summary.json
└── sse-constant-vus-summary.json
```

이 명령은 모든 **Baseline 기능**을 확인하는 것이며, AI 대량 생성, P-01 2배, P-02 혼합 부하,
SSE `10 → 30 → 70`, 한계 탐색과 장시간 안정성은 포함하지 않는다. 일괄 Baseline이 통과한 뒤에만
후속 단계를 실행한다.

### 5.3 단독 Baseline

Baseline은 합격 기준이 아니라 **낮고 재현 가능한 부하에서 얻는 비교 기준선**이다. 각 시나리오를
따로 실행해 p95/p99, 오류율, 실제 RPS, CPU·메모리·디스크와 로그를 기록한다.

```bash
# LT-01: 13 흐름/분 × 요청 3개 = 약 39 req/분, 5분
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/itinerary/constant-arrival.js

# LT-05: 약 30 req/분, 5분
CONFIRM_STAGING=true \
./scripts/test/run-k6.sh scenarios/generation/polling.js

# LT-02: 1 흐름/분 × 요청 2개, 5분
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/test/run-k6.sh scenarios/completion/constant-arrival.js
```

LT-02 결과와 서버 지표를 저장한 뒤, 해당 실행에서 사용한 항목만 되돌린다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh \
  --summary results/<LT-02-RUN-ID>/completion-constant-arrival-summary.json
```

예를 들어 summary의 `completion_attempts.count`가 50이면 `completion/flow.js`가 사용한 JSON 앞쪽 50명만
`is_completed=false`로 되돌린다. 직접 지정할 수도 있다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh --count 50
```

### 5.4 P-01 생성 증가 프로파일

P-01은 Staging AI Mock에서만 `지역 조회 → 여행 생성 → 생성 상태 폴링 → 일정 조회`를 실행한다.
각 생성은 새 여행·Job·일정을 남긴다. VU 하나는 사용자 하나에 고정되고, 생성 전체 흐름을 끝낸 뒤 같은
사용자로 다음 여행을 생성한다. 따라서 150명은 전체 생성 건수 제한이 아니라 `P01_*_MAX_VUS`와
`P01_RAMP_VU_*`의 **최대 동시 사용자 한도**다.

```bash
# 고정 생성 시작률
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/test/run-k6.sh scenarios/p01/constant-arrival.js

# 여행일 전 증가 패턴: 시작률 Ramp
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/test/run-k6.sh scenarios/p01/ramping-arrival.js

# 동시 생성 세션 Ramp: VU별 생성 전체 흐름을 순차 반복
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/test/run-k6.sh scenarios/p01/ramping-vus.js
```

P-01의 세 executor는 모두 VU별 사용자 고정 방식을 사용한다. 서로 다른 VU는 병렬 생성할 수 있지만,
하나의 VU는 이전 생성·폴링·일정 조회가 끝난 뒤에만 다음 생성 iteration을 시작하므로 같은 계정의 동시
생성은 발생하지 않는다. 생성 결과의 `p01_creation_attempts`, `p01_generation_completed`,
`p01_generation_failed`, `p01_generation_poll_requests`와 DB 증가량을 함께 기록한다.

LT-05는 새 여행을 만들지 않고 준비된 Job 상태 조회 한계만 분리 측정한다.

```bash
CONFIRM_STAGING=true GENERATION_POLL_RATE_PER_MINUTE=30 GENERATION_POLL_DURATION=10m \
  ./scripts/test/run-k6.sh scenarios/generation/polling.js

CONFIRM_STAGING=true GENERATION_POLL_RATE_PER_MINUTE=60 GENERATION_POLL_DURATION=10m \
  ./scripts/test/run-k6.sh scenarios/generation/polling.js
```

### 5.5 P-02 여행 당일 프로파일

P-02 고정 혼합은 기존 비교 기준이고, `ramping-spike.js`는 여행 당일의 읽기·완료 스파이크를 재현한다.
Ramp 구성은 **LT-01 RAR + LT-02 false→true RAR + 고정 SSE**다. Toggle은 P-02에 포함하지 않는다.
P-02의 SSE도 LT-06과 같은 `k6/x/sse` 연결 유지 흐름을 사용하며 VU 1개가 연결 1개를 유지한다.

```bash
# 기존 고정 혼합 + summary 기준 자동 복구
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/test/run-completion-with-reset.sh scenarios/p02/constant-mix.js

# 여행 당일 Ramp + 자동 복구
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/test/run-completion-with-reset.sh scenarios/p02/ramping-spike.js
```

P-02 Ramp의 읽기 rate는 `P02_ITINERARY_RAMP_*_RATE_PER_MINUTE`, 완료 rate는
`P02_COMPLETION_RAMP_*_RATE_PER_MINUTE`으로 독립 설정한다. stage 시간 역시 각 prefix의
`*_DURATION`으로 독립 설정하지만 두 duration 총합은 같아야 한다. 다르면 시작 전에 오류가 난다.
SSE 연결 수는 `P02_RAMP_SSE_CONNECTIONS`이고 duration은 두 Ramp 합계로 자동 설정된다.

SSE의 별도 한계 탐색은 고정 VU 또는 Ramp로 수행한다.

```bash
CONFIRM_STAGING=true SSE_CONNECTIONS=70 SSE_DURATION=10m \
  ./scripts/test/run-k6.sh scenarios/sse/constant-vus.js

CONFIRM_STAGING=true SSE_RAMP_PEAK_VUS=70 \
  ./scripts/test/run-k6.sh scenarios/sse/ramping-vus.js
```

70은 시스템 한계라는 뜻이 아니라 초기 탐색 단계다. 메모리, `sse_connection_errors`,
`sse_connection_opened`, `sse_connected_events`와 다음 실행의 handshake Smoke 결과를 기록한 뒤 다음 연결 수를 결정한다.

### 5.6 2배·한계 탐색·장시간 안정성

Baseline과 예상 피크가 안정적일 때만 요청률을 올린다.

```bash
# LT-01 2배: 26 흐름/분 → 약 78 req/분
ITINERARY_FLOW_RATE_PER_MINUTE=26 ITINERARY_READ_DURATION=10m \
  ./scripts/test/run-k6.sh scenarios/itinerary/constant-arrival.js

# LT-02 50회: 10 흐름/분 × 5분 = 50 iterations, HTTP 약 100개
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
COMPLETION_FLOW_RATE_PER_MINUTE=10 COMPLETION_DURATION=5m \
  ./scripts/test/run-k6.sh scenarios/completion/constant-arrival.js
```

`completion/flow.js`는 iteration마다 서로 다른 사용자·일정 항목을 사용한다. 현재 데이터가 150명이므로
한 실행에서 최대 150회의 실제 `false → true` 전환만 허용된다. 그 이상 필요하면 사용자별 미완료
항목을 추가로 준비해야 한다.

한계 탐색은 `1배 → 2배 → 4배`처럼 단계적으로 올리고, 품질 저하 직전의 마지막 안정 단계를 운영
용량 후보로 기록한다. 장시간 안정성은 P-02 기본 혼합을 먼저 30~60분 유지하고 메모리 증가,
재시작, 오류율, 응답시간 누적 악화를 확인한다.

## 6. 부하와 시간 조정 변수

| 시나리오 | 요청률·연결 수 | 실행 시간 | VU 관련 변수 |
| --- | --- | --- | --- |
| LT-01 | `ITINERARY_FLOW_RATE_PER_MINUTE` | `ITINERARY_READ_DURATION` | `ITINERARY_READ_PRE_ALLOCATED_VUS`, `ITINERARY_READ_MAX_VUS` |
| LT-02 | `COMPLETION_FLOW_RATE_PER_MINUTE` | `COMPLETION_DURATION` | `COMPLETION_PRE_ALLOCATED_VUS`, `COMPLETION_MAX_VUS` |
| LT-05 | `GENERATION_POLL_RATE_PER_MINUTE` | `GENERATION_POLL_DURATION` | `GENERATION_POLL_PRE_ALLOCATED_VUS`, `GENERATION_POLL_MAX_VUS` |
| LT-06 | `SSE_CONNECTIONS` | `SSE_DURATION` | 연결 수 자체가 VU 수 |
| P-01 전체 생성 | `P01_CREATION_RATE_PER_HOUR` | `P01_DURATION`, `P01_POLL_TIMEOUT_SECONDS`, `P01_POLL_INTERVAL_SECONDS` | `P01_PRE_ALLOCATED_VUS`, `P01_MAX_VUS` |
| P-02 혼합 | 위 LT-01·LT-02 요청률 + `SSE_CONNECTIONS` | `P02_DURATION` | 위 LT-01·LT-02 VU 변수 + SSE 연결 수 |
| API 직접 Ramp | 각 `*_RAMP_START_RPS` ~ `*_RAMP_PEAK_RPS` | 각 `*_RAMP_*_DURATION` | 각 `*_RAMP_PRE_ALLOCATED_VUS`, `*_RAMP_MAX_VUS` |

LT-01과 LT-02의 rate는 HTTP 요청 수가 아니라 **사용자 흐름 수/분**이다. LT-01은 흐름당 3개,
LT-02는 흐름당 2개의 HTTP 요청을 보낸다. 완료 변경 요청 본문은 Backend의 전역 SNAKE_CASE 계약에
맞춰 `{ "is_completed": true|false }`를 사용한다. LT-05는 요청 수/분이다.

## 7. 중단 기준과 결과 기록

현재 고정 SLO는 Baseline 뒤 확정한다. 아래 값은 **스크립트가 자동으로 요청률을 낮추는 조건이 아니다**. 운영자가 한계점을 기록한 뒤 다음 실행 여부를 판단하는 관측·중단 기준이다.

- 5xx 또는 Timeout이 반복 증가
- 이전 안정 단계보다 p95가 급격히 상승하고 회복하지 않음
- CPU·메모리가 지속 포화되거나 Task가 재시작됨
- `dropped_iterations` 발생
- 로그에 DB 연결 고갈, Lock, OOM, 외부 호출 폭증이 나타남

각 실행마다 최소 다음을 기록한다.

- 실행 ID, 시작·종료 시각, 시나리오와 환경변수
- BE·AI 배포 커밋과 Staging 설정 차이
- 실제 `http_reqs`, RPS, p95·p99, 오류율, Timeout, dropped iterations
- SSE 실행이면 `sse_connection_attempts`, `sse_connection_opened`, `sse_connected_events`,
  `sse_events_received`, `sse_connection_errors` 및 handshake Smoke의 `sse_handshake_200`
- 최대 CPU·메모리·디스크 사용률
- 상위 오류와 발생 시각
- 다음 단계 진행·재측정·중단 결정과 이유

`run-k6.sh`는 결과를 다음 위치에 저장한다.

```text
results/YYYY-MM-DD-HH-mm/<scenario>-summary.json

예: results/2026-09-30-20-25/itinerary-constant-arrival-summary.json
```

기본 폴더명은 실행을 시작한 KST 시각의 **년-월-일-시-분**이다. 같은 분에 서로 다른 시나리오를
실행하면 같은 폴더에 시나리오별 summary가 저장된다. 같은 분에 같은 시나리오를 다시 실행해도 기존
결과를 덮어쓰지 않고 `smoke-summary-2.json`, `smoke-summary-3.json`처럼 번호를 붙여 저장한다.
각 실행 시작 시 출력되는 `summary=...` 경로를 결과 기록과 완료 상태 초기화 명령에 사용한다.

`TEST_RUN_ID`와 시나리오 이름은 각각 `X-Test-Run-Id`, `X-Test-Scenario` 헤더로도 전달된다.

## 8. SSE 해석 주의

LT-06과 P-02의 SSE는 `k6/x/sse` client로 측정한다. 연결 유지 시나리오는 VU 1개가 열린 SSE 연결
1개를 점유하도록 설계됐다. 따라서 `constant-vus`와 `ramping-vus` 종료 시 다음 출력은 정상일 수 있다.

```text
0 complete and N interrupted iterations
```

이는 테스트 종료 또는 Ramp-down이 아직 열린 SSE 스트림을 중단했음을 뜻한다. 이 경우 iteration 완료 수가
아니라 `sse_connection_attempts`, `sse_connection_opened`, `sse_connected_events`,
`sse_events_received`, `sse_connection_errors`와 서버 메모리·로그를 판단 기준으로 사용한다.

반대로 `scenarios/sse/handshake-smoke.js`는 초기 `connected` 이벤트 뒤 클라이언트가 연결을 명시적으로
닫는다. 이 시나리오는 **1 iteration complete**여야 하며 `sse_handshake_200=1`,
`sse_connection_errors=0`을 확인한다. handshake Smoke가 실패하면 연결 유지 또는 P-02 SSE 부하를
시작하지 않는다.

현재 범위는 연결 성립과 Backend 초기 `connected` 이벤트 수신까지다. 브라우저 수준 자동 재연결,
`Last-Event-ID`, 임의 알림 이벤트의 장시간 전달 보장은 별도 시나리오가 필요하다.

## 9. Staging 시작·중지

`start-staging.sh`와 `stop-staging.sh`는 `.env`에 다음 값이 있을 때만 사용한다.

```dotenv
AWS_REGION=ap-northeast-2
STAGING_INSTANCE_ID=<staging-instance-id>
ECS_CLUSTER=<staging-cluster>
ECS_SERVICES=<service-a,service-b>
HEALTH_URL=https://<staging-api-domain>/health
CONFIRM_STAGING_CONTROL=true
```

필요 최소 권한은 `ec2:StartInstances`, `ec2:StopInstances`, `ec2:DescribeInstances`와 ECS 조회
권한이다.

```bash
./scripts/start-staging.sh

# 부하 실행 및 결과·CloudWatch·로그 수집

./scripts/stop-staging.sh
```

결과 수집이 끝나기 전에 Staging을 중지하지 않는다.

## 10. 자주 발생하는 실패

| 증상 | 우선 확인 |
| --- | --- |
| `401 Unauthorized` | JWT Secret 일치 여부, 24시간 만료, `sub=users.id`, `token_type=access` |
| `ECONNREFUSED`, `Connect Timeout` | Staging DNS/IP, 보안 그룹, 80/443, Nginx, ECS Task, Health Check |
| 생성 Job이 300초 내 완료되지 않음 | AI Mock 주소·SSE 경로·응답 계약·Backend 로그 |
| `already has test resource IDs` | 이미 성공한 사용자다. 불필요한 `--replace`를 사용하지 않음 |
| 과거 SQL verified count 불일치 | 로그의 사용자/여행 매핑, DB 대상, 여행 상태와 날짜 확인 후 `ROLLBACK` |
| completion 150회 초과 오류 | 한 실행의 고유 미완료 항목이 150개뿐임 |
| 초기화 도구가 50개만 되돌림 | 전달한 summary의 `iterations.count=50`이므로 정상 |
