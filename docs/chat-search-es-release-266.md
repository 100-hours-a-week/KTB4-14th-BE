# #266 — 채팅 메시지 ES 검색 적용 준비

## 커밋 범위

채팅 메시지 검색의 ES 어댑터, MySQL 권한 확인, NFC 검색, UTC 마이크로초/커서 처리, 메시지와 공동 커밋하는 Outbox, 비동기 Worker·재시도·lease·중복 방지, 서버 설정 및 관련 테스트를 함께 포함한다. 로컬 측정 결과와 생성된 SQL·JWT·덤프·실행 로그는 포함하지 않는다. `load-test/chat-search-lab` 도구는 이번 기능 커밋과 별도로 남긴다.

UTC 변경은 ES 문서와 DB 검색 결과의 시각·정렬·커서 일치를 위한 의존 변경이다. 채팅 목록 검색을 ES로 전환하는 변경은 아니다. 기본 메시지 검색은 기존 FULLTEXT 경로이며, 서버 프로필만 추가해도 검색이나 Outbox가 자동 활성화되지 않는다.

## 검증 기록 (2026-11-12)

- 최초 채팅 단위·HTTP 어댑터 검증은 27개 클래스, 144건 통과했다. 이후 최신 `development`(`ca04ef5`) 병합 후 Java 21 / Gradle offline으로 전체 비컨테이너 테스트 **85개 클래스, 404건 통과**. 실패·오류·건너뜀 0건.
- `bootJar` 성공. 배포 JAR 생성까지 확인했으며 실제 배포는 하지 않음.
- Docker daemon이 꺼져 있어 Testcontainers를 사용하는 7개 클래스(채팅 5개, 인증·알림 각 1개)는 병합 후 실행 대상에서 제외. 이들은 통과 건수에 포함하지 않음. 실제 MySQL/ES/Redis 통합 검증의 과거 결과와 이번 단위 검증을 구분함.
- 커밋 후보 코드·설정·문서에 대한 제한적 패턴 검사에서 JWT 형태·AWS access key 형태·GitHub token 형태·private key 블록을 발견하지 않음. 이 검사가 모든 종류의 비밀값 부재를 보증하지는 않음.

## 클라우드 전달 내용

전체 설정 표는 [서버 설정 문서](chat-search-es-deploy-config-01.md)를 사용한다. 코드의 현재 인증 방식은 Basic username/password다. 실제 비밀값은 Kubernetes Secret 등 배포 환경에서 주입한다.

| 담당 | 배포 전 확인 |
| --- | --- |
| 클라우드 | 백엔드 Pod에서 접근할 ES Service 주소·포트·HTTP/HTTPS, 인증 방식 확인. HTTPS 사설 CA 사용 시 JVM 신뢰 저장소 준비 |
| 클라우드/백엔드 | 기존 배포 프로필에 `search-server` 추가. DB 이름, 물리 인덱스, 대상 세대 확정 및 환경변수/Secret 연결 |
| DB 변경 담당 | 실제 DB에 Outbox DDL 수동 적용. 기존 메시지 시각의 기준과 JDBC UTC 세션 설정 검토 |
| ES 관리 담당 | 제공 매핑 템플릿의 대상 세대를 치환하고 물리 인덱스 생성. 서버 shard/replica·영속 저장·접근 권한 설정 |
| 백엔드 | 신규 메시지 저장→Outbox→색인→검색, 권한·원문/NFC·UTC·정렬·커서 검증 |
| 백엔드/DB·ES 담당 | 기존 메시지 backfill 실행 절차와 증분 동기화 경계 확정 및 이관 검증. 로컬 전용 스크립트는 서버 실행에 사용하지 않음 |

서버 준비 중에는 `AUDIGO_CHAT_MESSAGE_SEARCH_MODE=fulltext`, `AUDIGO_CHAT_SEARCH_SYNC_RECORD_ENABLED=false`, `AUDIGO_CHAT_SEARCH_SYNC_WORKER_ENABLED=false`로 기존 경로를 유지한다. 스키마/인덱스가 준비되면 기록·Worker를 켜고 동기화/기존 데이터 이관을 확인한 뒤 `es`로 검색을 전환한다.

검색 계정에는 대상 인덱스 읽기 권한, Worker 계정에는 대상 인덱스 매핑 조회와 문서 색인에 필요한 권한을 준비한다. 앱 계정에 인덱스 생성·삭제 권한을 전제로 하지 않는다. 인덱스 생성·backfill 권한은 별도 관리한다.

## 남은 검증과 제한

이 문서는 로컬 기능 코드와 서버 설정 준비 결과다. 실제 AWS/Kubernetes 연결·계정 권한·매핑 검증, 서버 기존 데이터 이관, 혼합 부하, 장애 복구 및 운영 전환은 아직 수행하지 않았다.

비동기 Outbox와 ES refresh를 사용하므로 메시지 전송 ACK 직후 검색 노출을 보장하지 않는다. 즉시 검색이 필수 요구라면 별도 일관성 설계가 필요하다. ES 장애를 빈 검색 결과로 바꾸지 않으며, `instr` 복귀 시 MySQL과 NFC ES의 검색 규칙이 완전히 같다는 보장도 없다.

푸시 준비 과정에서 원격을 fetch하고 `origin/development`의 `ca04ef5`를 기능 브랜치에 충돌 없이 병합했다. 최신 인증·보안·캐시·CI 변경을 포함한 상태에서 위 404건과 배포 JAR 생성을 검증했다. 서버 배포나 PR 생성은 이 검증에 포함되지 않는다.
