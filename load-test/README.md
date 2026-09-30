# Audigo Staging 부하테스트

Spring Boot 소스는 수정하지 않고, **Staging API에만** k6 요청을 보내기 위한 실행 도구다.
운영 API `https://api.audigo.kr`는 코드에서 차단하며, 이를 우회하는 환경변수는 없다.

## 현재 제공하는 시나리오

| 파일 | 설계 ID | 흐름 | 기본 부하 |
| --- | --- | --- | --- |
| `scenarios/smoke.js` | LT-01, LT-02, LT-04, LT-05 | 단일 API 계약 확인, 선택적으로 완료·생성 | VU 1회 |
| `scenarios/itinerary-read.js` | LT-01 | upcoming → recent → itinerary | 13 흐름/분, API 약 39 req/분 |
| `scenarios/completion.js` | LT-02 | completion → itinerary 재조회 | 1 흐름/분 |
| `scenarios/generation-polling.js` | LT-05 | 준비된 generation job 상태 조회 | 30 req/분 (약 0.5 RPS) |
| `scenarios/sse.js` | LT-06 | unread-count → SSE 연결 유지 | 연결 1개, 1분 |

LT-03 카카오 Maps SDK 검색은 FE가 직접 호출하므로 Backend k6 부하 범위에서 제외한다.

## 사전 준비

1. k6 CLI를 설치한다. (`k6 version`으로 확인)
2. `cp load-test/.env.example load-test/.env` 후 Staging 값만 입력한다.
   - `BASE_URL`은 Staging API URL이다.
   - `CONFIRM_STAGING=true` 없이는 어떤 시나리오도 시작되지 않는다.
   - `.env`와 실제 테스트 데이터 파일은 gitignore 대상이다.
3. `cp load-test/data/test-ids.example.json load-test/data/test-ids.json` 후 전용
   `loadtest-` 사용자별 여행·일정 항목·생성 Job ID를 입력한다.
   - LT-02와 LT-06은 VU마다 서로 다른 사용자와 일정 항목이 필요하다.
   - `SSE_CONNECTIONS=70`이라면 최소 70개 계정/토큰이 필요하다.
4. 생성 Smoke가 필요하면
   `cp load-test/data/create-request.example.json load-test/data/create-request.json` 후
   실제 Staging region ID와 유효한 요청 본문으로 바꾼다.
5. Staging이 `main`과 동일한 이미지·JVM 옵션·Task 리소스·스키마인지 확인하고,
   운영 DB가 아닌 테스트 데이터만 사용한다.

### 첫 사용자 시딩

`test-ids.json`의 `travelPlanId`, `itineraryItemId`, `generationJobId`가 `null`인 것은
아직 해당 사용자의 미래 생성 완료 여행을 만들지 않았다는 뜻이다. 이 ID가 있어야 일정
조회·완료 처리·생성 상태 폴링 시나리오가 각각의 리소스를 호출할 수 있다.

Staging의 **AI Mock 라우팅과 Health Check를 먼저 확인한 뒤**, 아래 명령으로 사용자 한 명의
미래 여행을 생성한다. 이 도구는 생성 완료까지 폴링하고, 미완료 일정 항목 하나를 찾아
같은 사용자의 세 ID를 `data/test-ids.json`에 원자적으로 기록한다. Spring Boot 코드는
수정하지 않는다.

```bash
cd load-test
cp data/create-request.example.json data/create-request.json
# region_id와 요청 내용은 Staging에서 유효한 값으로 편집한다.

ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true \
  ./scripts/seed-test-user.sh --user-id 1
```

`BASE_URL`과 `CONFIRM_STAGING=true`는 `.env`에서 읽는다. 운영 주소는 이 스크립트도
차단한다. 이미 ID가 기록된 사용자에 다시 생성하려면 실수 방지를 위해 명시적으로
`--replace`가 필요하다.

이 단계는 미래 여행만 준비한다. `recent` Smoke용 과거 생성 완료 여행은 생성 API가 과거
일정을 허용하지 않으므로, 별도의 Staging 전용 DB 시딩으로 준비한다. 미래 여행 한 건을
먼저 성공시킨 뒤에만 전체 150명으로 확대한다.

## Staging JWT 자동 발급

`scripts/generate-test-tokens.mjs`는 Staging Backend가 사용하는 `AUDIGO_JWT_SECRET`으로
`data/test-ids.json`의 모든 사용자에 대해 HS256 Access Token을 다시 발급한다. Backend의
`sub`, `iat`, `exp`, `token_type: access` 계약을 그대로 따른다. 기본 TTL은 현재 Staging 설정과
같은 **86,400초(24시간)** 이다.

Secret은 Git, `.env`, 명령 출력에 저장하지 않는다. Staging Secret 접근 권한이 있는 실행자만
안전한 Secret Manager 조회 또는 일회성 셸 환경변수로 전달한다.

```bash
cd load-test

# data/test-ids.json에 사용자 ID와 관련 테스트 데이터가 먼저 있어야 한다.
read -rs STAGING_JWT_SECRET && export STAGING_JWT_SECRET
printf '\n'
node scripts/generate-test-tokens.mjs
unset STAGING_JWT_SECRET

# TTL을 명시적으로 바꾸려면 (초 단위)
read -rs STAGING_JWT_SECRET && export STAGING_JWT_SECRET
printf '\n'
node scripts/generate-test-tokens.mjs --ttl 86400
unset STAGING_JWT_SECRET
```

성공하면 기존 사용자별 `accessToken`만 교체하고, 파일 권한을 소유자 읽기/쓰기로 제한한다.
실제 테스트 전마다 다시 실행한다. Staging JWT Secret을 교체하면 이전에 발급된 모든 토큰이
즉시 무효화된다.

## 실행 순서

```bash
cd load-test

# 0. Staging 준비 (리소스 식별자와 최소 IAM 권한을 .env에 넣은 뒤에만 실행)
./scripts/start-staging.sh

# 1. 읽기 Smoke — 외부 AI/Kakao 호출 및 DB 쓰기 없음
./scripts/run-k6.sh scenarios/smoke.js

# 2. LT-01 Baseline
./scripts/run-k6.sh scenarios/itinerary-read.js

# 3. LT-05 Baseline / P-01
./scripts/run-k6.sh scenarios/generation-polling.js

# 4. LT-02 쓰기 테스트 — 전용 테스트 데이터에서만 명시적으로 허용
ALLOW_WRITE_TESTS=true ./scripts/run-k6.sh scenarios/completion.js

# 5. LT-06 SSE — 1 → 10 → 30 → 70으로 단계적으로 증가
SSE_CONNECTIONS=10 SSE_DURATION=5m ./scripts/run-k6.sh scenarios/sse.js

# 결과를 수집한 뒤에만 중지
./scripts/stop-staging.sh
```

`run-k6.sh`는 `results/<TEST_RUN_ID>/<scenario>-summary.json`에 k6 요약을 저장한다.
실행 ID는 `X-Test-Run-Id`, 시나리오는 `X-Test-Scenario` 요청 헤더로 서버 로그에 남긴다.

## 생성 Smoke (LT-04)

실제 AI·카카오 호출은 소수 요청 Smoke로만 허용한다. 고부하 실행 전에는 반드시
Staging의 Mock 라우팅을 별도로 확인한다. 다음 명령은 실제 외부 연동 가능성을
의도적으로 명시한다.

```bash
ALLOW_WRITE_TESTS=true CONFIRM_EXTERNAL_SMOKE=true RUN_CREATION_SMOKE=true \
  ./scripts/run-k6.sh scenarios/smoke.js
```

`CREATION_POLL_ATTEMPTS`의 기본값은 3이며, 완료 시에만 생성된 일정 통합 조회까지 수행한다.

## 부하 조정 환경변수

| 시나리오 | 요청률/연결 수 | 시간 |
| --- | --- | --- |
| LT-01 | `ITINERARY_FLOW_RATE_PER_MINUTE` | `ITINERARY_READ_DURATION` |
| LT-02 | `COMPLETION_FLOW_RATE_PER_MINUTE` | `COMPLETION_DURATION` |
| LT-05 | `GENERATION_POLL_RATE_PER_MINUTE` | `GENERATION_POLL_DURATION` |
| LT-06 | `SSE_CONNECTIONS` | `SSE_DURATION` |

초기값은 Baseline 출발점이다. p95/p99·오류율·실제 RPS와 CPU·메모리·디스크·로그를
같은 시간대에 기록한 후에만 2배 및 한계 탐색 단계로 올린다. 고정 SLO threshold는
Baseline 결과와 서비스 목표가 정해진 뒤 추가한다.

## SSE 해석 주의

k6의 기본 HTTP 모듈은 열린 SSE 스트림의 이벤트를 읽어 검증하는 전용 SSE 클라이언트가
아니다. 이 시나리오는 VU 수만큼 연결을 유지하고, `sse_connection_attempts`와
Staging 로그·메모리를 함께 관측한다. 실행을 종료한 뒤 동일 단계로 재실행해 재연결을
확인한다. 이벤트 본문 검증이 필요하면 별도 SSE 지원 도구/확장을 추가하기 전에
기술 선택을 검토한다.

## Staging 시작·중지

두 AWS 스크립트는 `AWS_REGION`, `STAGING_INSTANCE_ID`, `HEALTH_URL`이 있어야 하고,
`CONFIRM_STAGING_CONTROL=true`가 없으면 종료한다. ECS 서비스가 있으면
`ECS_CLUSTER`, `ECS_SERVICES`(쉼표 구분)를 넣으면 시작 후 안정 상태를 기다린다.
필요 최소 권한은 `ec2:StartInstances`, `ec2:StopInstances`, `ec2:DescribeInstances` 및
ECS 조회 권한이다. 실제 리소스 식별자나 AWS 자격 증명은 저장소에 커밋하지 않는다.
