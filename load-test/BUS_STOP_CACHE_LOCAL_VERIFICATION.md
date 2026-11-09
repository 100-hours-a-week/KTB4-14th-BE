# 버스 정류장 캐시 로컬 검증

## 준비

이 절차는 `feat/redis-bus-stop-cache` 브랜치와 격리된 로컬 MySQL·Redis를 사용한다. 실제 TAGO에는 요청하지 않는다. 더미 여행·BUS 경로 20건과 인증 토큰은 [기준 부하테스트 계획](BUS_STOP_CACHE_LOAD_TEST_PLAN.md)의 `사전 조건 및 준비물`과 `실행 명령 예시`에 따라 준비한다. 기존 DB가 아니라 별도 테스트 스키마를 사용한다.

터미널 A에서 MySQL과 Redis를 시작한다.

```bash
cd /Users/sohyeon/audigo/BE
docker compose up -d mysql redis
docker compose exec redis redis-cli ping
```

터미널 B에서 TAGO 모의 서버를 시작한다. 도시·정류장·도착 조회에 `totalCount`를 반환한다.

```bash
cd /Users/sohyeon/audigo/BE/load-test
TAGO_STUB_DELAY_MS=100 node scripts/mock/tago-bus-server.mjs
```

터미널 C에서 백엔드를 실행한다. `SPRING_DATASOURCE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AUDIGO_JWT_SECRET`은 앞서 준비한 **격리 테스트 DB와 로컬 인증 설정**으로 지정한다. 비밀 값은 셸 기록에 남기지 않는다.

```bash
cd /Users/sohyeon/audigo/BE
export JPA_DDL_AUTO=validate
export AUDIGO_SQS_ENABLED=false
export AUDIGO_TRANSIT_STOP_CACHE_ENABLED=true
export TAGO_SERVICE_KEY=local-test-key
export TAGO_BUS_STOP_BASE_URL=http://127.0.0.1:18080/stops
export TAGO_BUS_ARRIVAL_BASE_URL=http://127.0.0.1:18080/arrivals
./gradlew bootRun
```

## 캐시 적중 확인

더미 데이터와 토큰이 준비된 뒤 터미널 D에서 첫 번째 사례의 ID와 토큰을 읽는다. 토큰을 화면에 출력하거나 결과 파일에 저장하지 않는다.

```bash
cd /Users/sohyeon/audigo/BE
DATA=load-test/data/bus-stop-cache-cases.local.json
TOKEN=$(jq -r '.users[0].accessToken' "$DATA")
PLAN_ID=$(jq -r '.users[0].travelPlanId' "$DATA")
ROUTE_ID=$(jq -r '.users[0].routeSegmentId' "$DATA")
docker compose exec redis sh -c 'redis-cli --scan --pattern "transit:stop:v2:*" | while IFS= read -r key; do redis-cli DEL "$key" >/dev/null; done'
curl -sS -X POST http://127.0.0.1:18080/__admin/reset
curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/api/travel-plans/$PLAN_ID/routes/$ROUTE_ID/bus-arrivals" | jq '.data'
curl -sS http://127.0.0.1:18080/__admin/metrics | jq
curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/api/travel-plans/$PLAN_ID/routes/$ROUTE_ID/bus-arrivals" | jq '.data'
curl -sS http://127.0.0.1:18080/__admin/metrics | jq
```

새 Redis를 사용하고 BUS leg가 하나인 첫 경로라면, 첫 요청 뒤 도시·정류장·도착 조회가 각각 1회다. 두 번째 요청 뒤 도시·정류장 조회는 그대로이고 **도착 조회만 2회**가 된다. `city_code`와 `node_id` 쿼리 파라미터를 직접 넣으면 정류장 후보 조회를 우회하므로 넣지 않는다.

Redis에는 `transit:stop:v2:` 접두사의 도시 키와 정류장 키가 생긴다. `TTL`은 쓰기 직후 도시 약 77,760~95,040초, 정류장 약 19,440~23,760초 범위다. 키가 이미 존재했다면 남은 TTL은 이보다 작을 수 있다.

```bash
docker compose exec redis redis-cli --scan --pattern 'transit:stop:v2:*'
docker compose exec redis redis-cli TTL transit:stop:v2:cities
```

## 빈 결과와 Redis 장애 확인

빈 결과를 확인하려면 **격리 테스트 DB에서만** 테스트용 BUS leg 하나의 `boarding_stop_name`을 `없는정류장`으로 변경하고, 그 경로를 두 번 호출한다. 첫 요청에서 정류장 스텁 호출이 증가하고 두 번째 요청에서는 증가하지 않아야 한다. 해당 정류장 키의 TTL은 300초다. 확인 뒤 원래 정류장명으로 되돌린다. 정류장 정보가 없으므로 이 경우 `available=false`가 정상이다.

Redis 장애 우회는 백엔드와 TAGO 모의 서버가 실행 중인 상태에서 로컬 Redis만 중단해 확인한다. `docker compose stop redis` 후 같은 경로를 호출하면 캐시 대신 TAGO의 도시·정류장 조회가 발생해야 한다. 응답과 백엔드의 캐시 오류 로그를 확인하고 `docker compose up -d redis`로 복구한다. 이 단계는 다른 로컬 기능도 같은 Redis를 사용하므로 **격리된 개발 환경에서만** 진행한다.

## 15분 비교 측정

단건 검증 뒤 [기준 부하테스트 계획](BUS_STOP_CACHE_LOAD_TEST_PLAN.md)과 동일한 더미 데이터·스텁 지연·5 RPS·80/20 분포로 15분 실행한다. 실행 ID만 `bus-cache-after-mixed-01`처럼 구분한다. 비교할 값은 API p95/p99, 유효 응답, 도시·정류장·도착 스텁 호출 수다. 도착 정보는 이번 캐시 대상이 아니므로 호출 수가 요청 수에 가깝게 유지되는지 확인한다.

```bash
cd /Users/sohyeon/audigo/BE/load-test
BASE_URL=http://127.0.0.1:8080 CONFIRM_STAGING=true CONFIRM_TAGO_STUB=true \
TEST_DATA_FILE=data/bus-stop-cache-cases.local.json TEST_RUN_ID=bus-cache-after-mixed-01 \
BUS_CACHE_PATTERN=mixed BUS_CACHE_HOT_PERCENT=80 BUS_CACHE_TARGET_RPS=5 BUS_CACHE_DURATION=15m \
./scripts/test/run-k6.sh scenarios/bus-stop/cache-lookup.js
```
