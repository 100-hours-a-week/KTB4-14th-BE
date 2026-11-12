# ES-DEPLOY-CONFIG-01 — 서버용 ES 연결·검색·동기화 설정 분리

## 변경 목적과 범위

로컬 PoC에서 검증한 검색·Outbox 코드를 서버에서도 설정으로 사용할 수 있도록 연결 경로를 추가한다. 기존 `search-lab` / `search-sync-lab`의 주소·DB·인덱스 제한은 유지한다. 실험용 실행/색인 스크립트도 계속 로컬 전용이다. 이 변경은 서버 배포, 기존 데이터 이관, 운영 전환을 실행하지 않는다.

기존 배포 프로필에 `search-server`를 추가한다. 예: `prod,search-server`. `search-server`와 두 lab 프로필을 함께 사용하면 시작을 거부한다. 서버 요청에는 lab 전용 응답 헤더를 추가하지 않는다.

프로필만 추가하면 기존 FULLTEXT 검색을 유지하며 Outbox 기록·Worker는 기본 비활성이다. 검색 전환, 기록 활성화, Worker 활성화는 각각 독립 설정이다. 따라서 서버 설정 누락 때문에 자동으로 로컬 ES를 사용하는 일이 없다.

## 클라우드에 전달할 설정

| 환경변수 | 의미 / 조건 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 기존 서버 프로필 + `search-server` |
| `AUDIGO_CHAT_MESSAGE_SEARCH_MODE` | `fulltext`(기본), `instr`, `es`. ES 검색 전환 시에만 `es` |
| `AUDIGO_CHAT_ES_URL` | 백엔드에서 접근 가능한 명시적 HTTP 또는 HTTPS origin. 예: `https://elasticsearch.search.svc.cluster.local:9200` (예시 주소) |
| `AUDIGO_CHAT_ES_USERNAME` / `AUDIGO_CHAT_ES_PASSWORD` | 검색용 Basic 인증. `es` 검색 시 필수, 서버용 기본값 없음 |
| `AUDIGO_CHAT_ES_INDEX` | 미리 생성·검증한 단일 물리 인덱스. 소문자 영숫자로 시작하며 영숫자·점·밑줄·하이픈, 최대 100자 |
| `AUDIGO_CHAT_ES_TEXT_CONTRACT` | `nfc-v1` (서버 프로필 기본값). 서버 ES/동기화는 baseline 불허 |
| `AUDIGO_CHAT_SEARCH_SYNC_DATABASE_NAME` | 실제 JDBC URL의 DB 이름을 다시 명시하여 대상 확인. DB를 생성하거나 JDBC URL을 변경하는 값은 아님 |
| `AUDIGO_CHAT_SEARCH_SYNC_TARGET_INDEX` | 읽기 인덱스와 같아야 함. 생략 시 `AUDIGO_CHAT_ES_INDEX` 사용 |
| `AUDIGO_CHAT_SEARCH_SYNC_TARGET_GENERATION` | 인덱스 매핑 `_meta.sync_generation`과 맞출 세대. 최대 64자, 영숫자로 시작하며 영숫자·점·밑줄·하이픈 |
| `AUDIGO_CHAT_SEARCH_SYNC_RECORD_ENABLED` | 메시지 트랜잭션에 Outbox 기록 여부. 기본 `false` |
| `AUDIGO_CHAT_SEARCH_SYNC_WORKER_ENABLED` | Worker 처리 여부. 기본 `false` |
| `AUDIGO_CHAT_SEARCH_SYNC_ES_URL` | 생략 시 읽기 URL 사용. 지정하면 읽기 URL과 같아야 함 (끝 `/` 차이는 허용) |
| `AUDIGO_CHAT_SEARCH_SYNC_ES_USERNAME` / `AUDIGO_CHAT_SEARCH_SYNC_ES_PASSWORD` | Worker용 Basic 인증. Worker 활성 시 필수. 검색 계정 비밀번호를 자동 복사하지 않음 |

비밀값은 Kubernetes Secret 등 기존 배포 시스템에서 환경변수로 주입한다. Git·문서·채팅에 실제 비밀번호를 기록하지 않는다. 현재 클라이언트는 Basic 인증이며 API key / AWS SigV4 인증은 구현 범위 밖이다.

URL은 HTTP(S), hostname, 선택 포트만 허용한다. URL 내 계정/비밀번호, 하위 경로, query, fragment, wildcard 주소를 허용하지 않는다. `0.0.0.0` / `::` 수신 주소도 접속 대상으로 사용하지 않는다. 읽기/쓰기 주소를 동적으로 요청에서 받지 않는다. 이 설정은 네트워크 방화벽을 열지 않는다.

HTTPS는 JVM 기본 신뢰 저장소로 인증서를 검증한다. 사설 CA를 사용하면 클라우드에서 해당 CA를 JVM 신뢰 저장소에 등록해야 하며, 이 코드는 인증서·hostname 검증을 끄지 않는다. HTTP가 필요한 내부 배치는 네트워크 접근 제한을 클라우드에서 별도로 구성한다.

## 적용 순서

1. 실제 DB·ES endpoint·물리 인덱스·세대·계정을 확정한다. Kubernetes에서 ES가 별도 Pod이면 backend Pod의 `127.0.0.1` 대신 ES Service 주소를 사용한다.
2. 실제 DB 스키마와 시간대를 검토하고 `src/main/resources/db/chat-search-sync/001-create-outbox.sql`을 승인된 DB 변경 절차로 적용한다. SQL은 자동 적용하지 않는다. prod의 기존 JPA validate 동작은 변경하지 않는다. JDBC 연결의 UTC 세션 설정(`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`)도 확인한다. 이 변경은 기존 JDBC URL이나 과거 데이터의 시각을 자동 변경하지 않는다.
3. `src/main/resources/search/chat-message-sync-mapping.json`의 `__TARGET_GENERATION__`을 확정한 세대로 치환하여 물리 인덱스를 생성·검증한다. 템플릿의 shard/replica 값은 로컬 검증 출발값이므로 서버 가용성 정책을 별도로 확정한다. 앱은 매핑을 생성하지 않는다.
4. 검색은 기존 모드를 유지한 채 Outbox 기록·Worker를 준비된 대상에 명시적으로 활성화한다. Worker는 매핑 계약/세대 확인 후 메시지 ID로 PUT한다. 인덱스·세대가 바뀌면 기존 대기 이벤트 이관 계획을 먼저 세운다.
5. 기존 메시지 backfill, 신규 메시지 동기화, 건수/내용/UTC/정렬/권한을 서버에서 검증한 후 검색 모드를 `es`로 전환한다. 로컬 스크립트의 대상 제한을 제거하여 서버에 실행하지 않는다. 서버 이관 실행 절차는 별도로 준비한다.

검색/색인 HTTP는 JDBC 트랜잭션 밖에서 실행한다. 실패를 빈 결과로 바꾸지 않고, 재시도 및 메시지 ID 기반 중복 방지 동작을 유지한다. 매핑/세대는 Worker가 호출 시 검사하며, 설정 검증 성공만으로 서버 연결·인덱스·권한의 실제 동작이 검증되는 것은 아니다. 물리 인덱스 이름은 설정 검증으로 alias와 구분할 수 없으므로 실제 대상 점검이 필요하다.

`instr` 복귀는 설정으로 가능하지만 NFC ES와 MySQL의 검색 규칙이 완전히 같다는 보장은 없다. 운영 복구 정책은 별도 결정한다.

현재 비동기 Outbox + ES refresh 흐름은 ACK 직후 즉시 검색을 보장하지 않는다. 이번 작업은 연결 설정 전환이며, 즉시 검색 요구를 충족시키는 일관성 설계는 별도 미해결 항목이다.

## 검증

서버 DNS/DB/인덱스 설정 허용, lab 제한 유지, lab/server 혼합 차단, 누락 인증·lab 비밀번호·불일치 대상 차단, 환경변수/YAML 바인딩, 검색/색인 생성자를 통한 HTTP 전송 및 NFC/UTC/세대 보존을 자동 테스트한다. 실제 AWS/Kubernetes 접속 및 배포는 수행하지 않는다.

2026-11-12 검증 결과: 아래 9개 테스트 클래스의 73건이 실패·오류·건너뜀 0건으로 통과했다. Java 21, Gradle offline 실행. HTTP 전송 테스트는 임시 로컬 HTTP 서버를 사용하며 실제 ES/MySQL/클라우드 데이터에 접속하지 않는다.

```bash
./gradlew test --offline \
  --tests '*ChatMessageSearchDeploymentTest' \
  --tests '*ChatMessageSearchModeTest' \
  --tests '*ChatMessageSearchOutboxPropertiesTest' \
  --tests '*ChatMessageEsSearchTest' \
  --tests '*ChatMessageEsIndexerConfigurationTest' \
  --tests '*ChatMessageSearchServiceTest' \
  --tests '*ChatMessageSearchControllerTest' \
  --tests '*SearchLabMetricsConfigurationTest' \
  --tests '*SearchSyncPerfMetricsConfigurationTest' --console=plain
```
