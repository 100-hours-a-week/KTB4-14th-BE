# Audigo Staging 부하테스트

Spring Boot 소스는 수정하지 않고 `load-test/`의 스크립트만 사용해 **Staging Backend와 MySQL**을
검증한다. 운영 API `https://api.audigo.kr`는 코드에서 차단하며, 운영 DB에는 사용자 생성·시딩·날짜
변경·초기화 SQL을 실행하지 않는다.

## 현재 진행 상태 (2026-09-30)

- Staging DB에 `loadtest-001`~`loadtest-150` 사용자 150명을 생성했다.
- 사용자별 JWT, 미래 여행의 `travelPlanId`, `itineraryItemId`, `generationJobId`가
  `data/test-ids.json`에 준비됐다.
- 사용자별 두 번째 여행을 생성한 뒤 과거 날짜로 변경했고, 과거 여행 보유 사용자 수 150명을
  확인했다.
- 기본 Smoke 결과는 HTTP 요청 5개 모두 성공, check 15/15 성공, 오류율 0%, p95 약 84.86ms였다.
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

| 파일 | 설계 ID | 흐름 | 기본 설정 | 5분 기준 목표 요청 |
| --- | --- | --- | --- | ---: |
| `scenarios/smoke.js` | LT-01·02·04·05 | 기본 읽기 5개, 쓰기·생성은 선택 | 1 VU, 1 iteration | 기본 5개 |
| `scenarios/itinerary-read.js` | LT-01 | upcoming → recent → itinerary | 13 흐름/분, 5분 | 65 흐름, 약 195개 |
| `scenarios/completion.js` | LT-02 | completion → itinerary 재조회 | 1 흐름/분, 5분 | 5 흐름, 약 10개 |
| `scenarios/generation-polling.js` | LT-05 | 준비된 Job 상태 조회 | 30요청/분, 5분 | 약 150개 |
| `scenarios/sse.js` | LT-06 | unread-count → SSE 연결 유지 | 연결 1개, 1분 | HTTP 1개 + SSE 1개 |
| `scenarios/p02.js` | P-02 | LT-01·LT-02·LT-06 동시 실행 | 읽기 13·완료 5 흐름/분, SSE 10개, 10분 | 혼합 프로파일 |

도착률 executor는 로컬 장비나 Staging이 처리하지 못하면 `dropped_iterations`가 생길 수 있으므로
표의 값은 목표치다. 실제 `http_reqs`, `iterations`, `dropped_iterations`는 결과 JSON에서 확인한다.

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
node scripts/initialize-test-ids.mjs
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
node scripts/initialize-test-ids.mjs --force
```

### 3.4 사용자별 JWT 150개 발급

JWT는 Staging Backend에 로그인 요청을 보내 발급받는 것이 아니라, Staging의
`AUDIGO_JWT_SECRET`과 같은 Secret으로 로컬에서 HS256 서명한다. Payload는 Backend 계약에 맞게
`sub`, `iat`, `exp`, `token_type: access`를 갖는다.

Secret을 `.env`나 명령행 인수에 쓰지 않는다.

```bash
read -rs STAGING_JWT_SECRET && export STAGING_JWT_SECRET
printf '\n'
node scripts/generate-test-tokens.mjs --ttl 86400
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
  ./scripts/seed-test-user.sh --user-id "$FIRST_USER_ID"
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
  ./scripts/seed-test-user.sh --user-id "$FIRST_USER_ID" --no-record \
  | tee "results/past-seed/user-${FIRST_USER_ID}.log"
```

과거 날짜는 실행 시점보다 이전이어야 한다. 다음 값은 2026-09-30 기준 예시다.

```bash
PAST_ARRIVAL='2026-09-01 10:00:00'
PAST_DEPARTURE='2026-09-02 18:00:00'

node scripts/generate-past-travel-sql.mjs \
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
./scripts/run-k6.sh scenarios/smoke.js
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
    ./scripts/seed-test-user.sh --user-id "$user_id"
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
    ./scripts/seed-test-user.sh --user-id "$user_id" --no-record \
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

node scripts/generate-past-travel-sql.mjs \
  --arrival "$PAST_ARRIVAL" \
  --departure "$PAST_DEPARTURE"
```

기존 작업처럼 첫 사용자는 이미 과거로 바뀌었고 사용자 2~150 로그만 있는 경우에는 다음처럼
JSON 배열의 두 번째 사용자부터 SQL을 만들 수 있다.

```bash
node scripts/generate-past-travel-sql.mjs \
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
./scripts/run-k6.sh scenarios/smoke.js
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
node scripts/generate-test-tokens.mjs --ttl 86400
unset STAGING_JWT_SECRET

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh

./scripts/run-k6.sh scenarios/smoke.js
```

## 5. 권장 부하테스트 진행 순서

### 5.1 Smoke

```bash
./scripts/run-k6.sh scenarios/smoke.js
```

쓰기 Smoke가 필요하면 완료 상태를 즉시 되돌린다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true RUN_COMPLETION_SMOKE=true \
  ./scripts/run-k6.sh scenarios/smoke.js

CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh --count 1
```

실제 AI·카카오 외부 연동 Smoke는 소수 요청으로만 수행한다. AI Mock 시딩과 혼동하지 않는다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
CONFIRM_EXTERNAL_SMOKE=true RUN_CREATION_SMOKE=true \
  ./scripts/run-k6.sh scenarios/smoke.js
```

### 5.2 전체 Baseline 일괄 실행

현재 구현된 Backend 시나리오를 모두 한 번씩 실행하려면 다음 명령을 사용한다. 약 16~18분이 걸리며
하나의 `results/YYYY-MM-DD-HH-mm/` 폴더에 Smoke, LT-01, LT-05, LT-02, LT-06 결과와 전체 로그를
저장한다.

```bash
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
  ./scripts/run-baseline-suite.sh
```

일괄 실행은 다음을 자동 수행한다.

1. 테스트 데이터 150명, 리소스 ID 중복, JWT subject·만료 시간 검증
2. Staging Health Check
3. 기본 읽기 Smoke
4. LT-01 일정 조회 Baseline 5분
5. LT-05 생성 상태 폴링 Baseline 5분
6. 완료 상태 전체 초기화 후 LT-02 Baseline 5분
7. LT-02에서 실제 사용한 항목만 summary 기준으로 다시 초기화
8. LT-06 SSE 연결 1개 Baseline 1분
9. check 실패·HTTP 실패·dropped iteration 검증

중간에 LT-02가 중단되면 안전장치가 전체 완료 항목 초기화를 시도한다. 결과 폴더에는 다음 파일이
생긴다.

```text
results/YYYY-MM-DD-HH-mm/
├── suite.log
├── suite-config.txt
├── smoke-summary.json
├── itinerary-read-summary.json
├── generation-polling-summary.json
├── completion-summary.json
└── sse-summary.json
```

이 명령은 모든 **Baseline 기능**을 확인하는 것이며, AI 대량 생성, P-01 2배, P-02 혼합 부하,
SSE `10 → 30 → 70`, 한계 탐색과 장시간 안정성은 포함하지 않는다. 일괄 Baseline이 통과한 뒤에만
후속 단계를 실행한다.

### 5.3 단독 Baseline

Baseline은 합격 기준이 아니라 **낮고 재현 가능한 부하에서 얻는 비교 기준선**이다. 각 시나리오를
따로 실행해 p95/p99, 오류율, 실제 RPS, CPU·메모리·디스크와 로그를 기록한다.

```bash
# LT-01: 13 흐름/분 × 요청 3개 = 약 39 req/분, 5분
./scripts/run-k6.sh scenarios/itinerary-read.js

# LT-05: 약 30 req/분, 5분
./scripts/run-k6.sh scenarios/generation-polling.js

# LT-02: 1 흐름/분 × 요청 2개, 5분
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
  ./scripts/run-k6.sh scenarios/completion.js
```

LT-02 결과와 서버 지표를 저장한 뒤, 해당 실행에서 사용한 항목만 되돌린다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh \
  --summary results/<LT-02-RUN-ID>/completion-summary.json
```

예를 들어 summary의 `iterations.count`가 50이면 `completion.js`가 사용한 JSON 앞쪽 50명만
`is_completed=false`로 되돌린다. 직접 지정할 수도 있다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh --count 50
```

### 5.4 P-01 생성 증가 프로파일

현재 자동화된 P-01 고부하는 **준비된 Job을 반복 조회하는 LT-05**다. 새로운 여행을 대량 생성하지
않으므로 AI Mock 처리량과 생성 쓰기 성능을 측정하는 시나리오가 아니라 Backend·MySQL의 생성 상태
조회 한계를 측정한다.

```bash
# 예상 피크 약 0.494 RPS에 가까운 30 req/분
GENERATION_POLL_RATE_PER_MINUTE=30 \
GENERATION_POLL_DURATION=10m \
  ./scripts/run-k6.sh scenarios/generation-polling.js

# 2배 단계
GENERATION_POLL_RATE_PER_MINUTE=60 \
GENERATION_POLL_DURATION=10m \
  ./scripts/run-k6.sh scenarios/generation-polling.js
```

전체 생성 흐름 LT-04는 Mock 또는 실제 외부 연동 Smoke로만 실행한다. 대량 생성 시나리오가 필요하면
외부 API 비용·실패 패턴과 테스트 데이터 정리 정책을 별도로 확정한 뒤 추가한다.

### 5.5 P-02 여행 당일 프로파일

P-02는 LT-01, LT-02, LT-06의 개별 Baseline이 성공한 뒤 하나의 시나리오로 동시에 실행한다. 기본값은
읽기 `13 흐름/분`, 완료·재조회 `5 흐름/분`, SSE `10개`, 실행 시간 `10분`이다.

```bash
ALLOW_WRITE_TESTS=true ./scripts/run-k6.sh scenarios/p02.js
```

요청률·연결 수·시간을 바꾸려면 같은 명령 앞에 값을 지정한다.

```bash
ALLOW_WRITE_TESTS=true P02_DURATION=10m ITINERARY_FLOW_RATE_PER_MINUTE=13 COMPLETION_FLOW_RATE_PER_MINUTE=5 SSE_CONNECTIONS=10 ./scripts/run-k6.sh scenarios/p02.js
```

P-02가 완료 처리한 항목은 결과 파일의 `completion_attempts`를 기준으로 복구한다.

```bash
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true ./scripts/reset-completions.sh --summary results/<P02-RUN-ID>/p02-summary.json
```

SSE는 다음 순서로 별도 한계도 확인한다.

```bash
SSE_CONNECTIONS=1  SSE_DURATION=5m  ./scripts/run-k6.sh scenarios/sse.js
SSE_CONNECTIONS=10 SSE_DURATION=5m  ./scripts/run-k6.sh scenarios/sse.js
SSE_CONNECTIONS=30 SSE_DURATION=10m ./scripts/run-k6.sh scenarios/sse.js
SSE_CONNECTIONS=70 SSE_DURATION=10m ./scripts/run-k6.sh scenarios/sse.js
```

70은 시스템 한계라는 뜻이 아니라 초기 탐색 단계다. 각 단계의 메모리, 연결 오류와 재실행 시 재연결
성공 여부를 보고 다음 연결 수를 정한다.

### 5.6 2배·한계 탐색·장시간 안정성

Baseline과 예상 피크가 안정적일 때만 요청률을 올린다.

```bash
# LT-01 2배: 26 흐름/분 → 약 78 req/분
ITINERARY_FLOW_RATE_PER_MINUTE=26 ITINERARY_READ_DURATION=10m \
  ./scripts/run-k6.sh scenarios/itinerary-read.js

# LT-02 50회: 10 흐름/분 × 5분 = 50 iterations, HTTP 약 100개
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
COMPLETION_FLOW_RATE_PER_MINUTE=10 COMPLETION_DURATION=5m \
  ./scripts/run-k6.sh scenarios/completion.js
```

`completion.js`는 iteration마다 서로 다른 사용자·일정 항목을 사용한다. 현재 데이터가 150명이므로
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
| P-02 혼합 | 위 LT-01·LT-02 요청률 + `SSE_CONNECTIONS` | `P02_DURATION` | 위 LT-01·LT-02 VU 변수 + SSE 연결 수 |

LT-01과 LT-02의 rate는 HTTP 요청 수가 아니라 **사용자 흐름 수/분**이다. LT-01은 흐름당 3개,
LT-02는 흐름당 2개의 HTTP 요청을 보낸다. 완료 변경 요청 본문은 Backend의 전역 SNAKE_CASE 계약에
맞춰 `{ "is_completed": true|false }`를 사용한다. LT-05는 요청 수/분이다.

## 7. 중단 기준과 결과 기록

현재 고정 SLO는 Baseline 뒤 확정한다. 그 전에도 다음 현상이 보이면 현재 단계를 중단한다.

- 5xx 또는 Timeout이 반복 증가
- 이전 안정 단계보다 p95가 급격히 상승하고 회복하지 않음
- CPU·메모리가 지속 포화되거나 Task가 재시작됨
- `dropped_iterations` 발생
- 로그에 DB 연결 고갈, Lock, OOM, 외부 호출 폭증이 나타남

각 실행마다 최소 다음을 기록한다.

- 실행 ID, 시작·종료 시각, 시나리오와 환경변수
- BE·AI 배포 커밋과 Staging 설정 차이
- 실제 `http_reqs`, RPS, p95·p99, 오류율, Timeout, dropped iterations
- 최대 CPU·메모리·디스크 사용률
- 상위 오류와 발생 시각
- 다음 단계 진행·재측정·중단 결정과 이유

`run-k6.sh`는 결과를 다음 위치에 저장한다.

```text
results/YYYY-MM-DD-HH-mm/<scenario>-summary.json

예: results/2026-09-30-20-25/itinerary-read-summary.json
```

기본 폴더명은 실행을 시작한 KST 시각의 **년-월-일-시-분**이다. 같은 분에 서로 다른 시나리오를
실행하면 같은 폴더에 시나리오별 summary가 저장된다. 같은 분에 같은 시나리오를 다시 실행해도 기존
결과를 덮어쓰지 않고 `smoke-summary-2.json`, `smoke-summary-3.json`처럼 번호를 붙여 저장한다.
각 실행 시작 시 출력되는 `summary=...` 경로를 결과 기록과 완료 상태 초기화 명령에 사용한다.

`TEST_RUN_ID`와 시나리오 이름은 각각 `X-Test-Run-Id`, `X-Test-Scenario` 헤더로도 전달된다.

## 8. SSE 해석 주의

k6 기본 HTTP 모듈은 전용 SSE 이벤트 검증 클라이언트가 아니다. 현재 시나리오는 VU 수만큼 연결을
유지하면서 `sse_connection_attempts`, 응답, 서버 로그와 메모리를 함께 본다. 실행 종료 후 같은
연결 수로 다시 실행해 재연결 안정성을 확인한다. 이벤트 본문·Last-Event-ID까지 검증하려면 별도
SSE 지원 도구 또는 확장을 기술 검토 후 추가한다.

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
