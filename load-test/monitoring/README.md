# 로컬 부하테스트 모니터링

> **JWT 전송 보안:** 원격 HTTP 지표 수집은 기본 거부한다. HTTPS 또는 Backend SSH 터널을
> 사용하고, 후자는 `MONITORING_BACKEND_URL=http://127.0.0.1:19106`으로 지정한다.
> `BASE_URL`은 기존 k6 대상 주소로 유지한다. 격리된 Staging JWT의 평문 전송 위험을 이해하고
> 명시적으로 허용할 때만 `MONITORING_ALLOW_INSECURE_HTTP=true`를 사용한다. 자동으로 켜지지 않는다.

`scripts/monitoring/monitor.sh`는 Staging의 Backend Actuator와 DB EC2 MySQL Exporter를 Mac에서 수집한다.
Prometheus와 Grafana는 Mac Docker에서 실행한다. Spring Boot 소스·이미지와 MySQL 애플리케이션 컨테이너는
수정하지 않는다.

## 범위와 네트워크 경계

```text
Staging Backend /actuator/metrics
  └─ Mac loopback bridge 127.0.0.1:19105
       └─ Prometheus 127.0.0.1:19090
            └─ Grafana 127.0.0.1:13000

DB EC2 MySQL Exporter 127.0.0.1:9104
  └─ SSH tunnel
       └─ Mac 127.0.0.1:19104
            └─ Prometheus
```

MySQL Exporter와 Grafana·Prometheus는 public 포트를 열지 않는다. `stop`은 Mac의 bridge·Compose 서비스만
중지하며, SSH tunnel·EC2·DB exporter·Docker volume은 삭제하거나 중지하지 않는다.

## 시작 전 확인

1. `.env`의 `BASE_URL`은 Staging origin이고 `CONFIRM_STAGING=true`다.
2. `ACCESS_TOKEN` 또는 `data/test-ids.json`에 raw Staging JWT가 있다. `Bearer ` 접두사는 넣지 않는다.
3. DB EC2 exporter가 `mysql_up 1`을 반환한다.
4. Mac SSH tunnel `127.0.0.1:19104 → DB EC2 127.0.0.1:9104`을 열어 둔다.
5. `curl -fsS http://127.0.0.1:19104/metrics | grep '^mysql_up '`가 `mysql_up 1`을 출력한다.

## 명령어

```bash
CONFIRM_STAGING=true \
./scripts/monitoring/monitor.sh prepare

CONFIRM_STAGING=true \
./scripts/monitoring/monitor.sh run \
  scenarios/api/itinerary-ramping-arrival.js

./scripts/monitoring/monitor.sh view

./scripts/monitoring/monitor.sh status

./scripts/monitoring/monitor.sh stop
```

`prepare`는 인증과 연결을 검증하지만 지속 수집은 시작하지 않는다. `run`은 실행 전·후 각각 기본 30초 동안
지표를 수집한다. Backend Actuator bridge의 기본 수집 간격은 5초이며 `MONITORING_INTERVAL_SECONDS`로 조절한다.
Prometheus의 scrape 간격은 `monitoring/prometheus.yml`에서 고정 5초다. `view`는 브라우저를 자동으로 열지 않고,
로컬 stack을 준비한 뒤 Grafana URL을 출력한다.

```bash
CONFIRM_STAGING=true \
MONITORING_PRE_SECONDS=0 \
MONITORING_POST_SECONDS=0 \
MONITORING_INTERVAL_SECONDS=5 \
MONITORING_READY_TIMEOUT_SECONDS=60 \
./scripts/monitoring/monitor.sh run \
  scenarios/api/itinerary-ramping-arrival.js
```

완료 처리 시나리오에서만 기존 복구 절차까지 연결하려면 `--reset`을 사용한다.

```bash
CONFIRM_STAGING=true \
ALLOW_WRITE_TESTS=true \
CONFIRM_COMPLETION_RESET=true \
./scripts/monitoring/monitor.sh run --reset \
  scenarios/api/completion-ramping-arrival.js
```

## 수집 지표와 해석

| 소스 | 주요 지표 |
| --- | --- |
| Backend Actuator | HikariCP active·idle·pending·max·timeout, JVM memory·GC·thread, `http.server.requests` timer |
| MySQL Exporter | `mysql_up`, connection/thread 상태, InnoDB·global status·global variables |
| 기존 CloudWatch | CPU·메모리는 기존 수집 경로에서 확인한다. 이 로컬 Grafana에는 자동으로 추가되지 않는다. |

Backend timer의 count·sum·max로 평균·누적·최대는 해석할 수 있지만 **p95를 계산할 수 없다**. p95는 k6 summary에서
확인한다. 또한 `http.server.requests`는 Actuator metrics 요청 자체를 포함한다. bridge는 기본 수집 주기마다
Backend에 대략 20~40회의 metrics 읽기 요청을 추가한다. `MONITORING_INTERVAL_SECONDS`를 크게 하면 이 Backend
읽기 요청 수는 줄지만, Prometheus의 bridge/MySQL scrape 간격은 고정 5초로 유지된다. 부하 결과에서는 이 관측
오버헤드를 함께 고려한다.

## 결과 파일과 보관

각 `run`은 기존 k6 결과 폴더 아래에 다음 파일을 남긴다.

```text
results/<run-id>/
├── <scenario>-summary.json
├── dashboard.html
└── monitoring/
    ├── run.json
    ├── backend-series.json
    ├── mysql-series.json
    ├── collection-errors.jsonl
    └── summary.json
```

- `run.json`은 단계별 UTC 시각과 종료 상태를 기록한다. Grafana 대시보드는 `Asia/Seoul` 시간대를 사용한다.
- `backend-series.json`, `mysql-series.json`은 실행 보고서 보관용 Prometheus query 결과다.
- `collection-errors.jsonl`은 수집 실패·누락을 기록한다. 실행 중 오류가 있어도 자동 감속하지 않는다.
- `summary.json`은 source별 series/sample 수와 수집 완전성 여부를 기록한다. 필요한 Actuator 지표가 없거나 수집이 실패하면 `complete=false`이며, 성공으로 표시하지 않는다.
- `dashboard.html`은 k6 웹 대시보드의 실행별 export다. 별도 `K6_WEB_DASHBOARD_EXPORT` 경로를 지정하면 그 경로를 유지한다.

Prometheus Docker volume은 14일 또는 2GB까지 보관한다. Grafana는 이 영구 시계열을 조회한다. 위 JSON은 Grafana
가 직접 import하는 형식이 아니다.

## 로컬 자격 증명과 정리

`monitoring/runtime/stack.env`는 Grafana admin 비밀번호를 생성해 저장한다. 파일은 mode `600`이며 Git에서 제외한다.
비밀번호는 Mac 로컬에서만 확인한다.

```bash
grep '^MONITORING_GRAFANA_PASSWORD=' monitoring/runtime/stack.env
```

비밀번호·JWT·JWT Secret·DB exporter 비밀번호를 채팅·로그·Git에 넣지 않는다.

## 이미지 출처

- [Prometheus documentation](https://prometheus.io/docs/prometheus/latest/)
- [Grafana documentation](https://grafana.com/docs/grafana/latest/)
- [mysqld_exporter](https://github.com/prometheus/mysqld_exporter)

Compose는 고정된 Prometheus `3.15.0`, Grafana `13.2.3` 이미지를 사용한다. 이미지 pin 또는 digest 변경은 별도
검증 뒤에만 수행한다.
