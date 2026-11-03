# Redis Pub/Sub 알림 전달 및 SSE 연결 관리

이슈: #245. BE는 커밋 이후 Redis 발행, 로컬 SSE 전달, heartbeat·연결 정리와 재동기화 안내를 제공한다.
FE의 재조회·중복 처리 구현 및 dev 환경 최종 통합 검증은 별도 작업이다.

## 전달 흐름

1. 여행 완료·실패, 매칭 성공, 최초 미읽음 채팅 알림은 기존 `NotificationService`에서 DB에 저장한다.
2. 알림 저장 트랜잭션의 `afterCommit`에서 `NotificationRealtimePublisher`가 JSON을 Redis에 발행한다.
3. 같은 알림 채널을 구독하는 모든 BE의 `RedisNotificationRealtimeListener`가 수신·검증한다.
4. 각 BE의 `NotificationSseService`가 수신자의 로컬 SSE 연결 모두에 기존 `notification` 이벤트를 보낸다.

발행한 BE도 구독 경로로만 전달한다. 알림 생성 서비스의 직접 SSE 전송은 제거하여 이중 전달을 피한다.
SSE 연결은 각 BE 메모리에 유지하며 Redis에는 연결 객체를 저장하지 않는다.
사용자가 어느 서버에도 연결되지 않았다면 실시간 전송 대상은 없지만 DB 알림은 유지된다.

## 기존 채팅과 동일한 이벤트 봉투

```json
{
  "recipientUserId": 7,
  "eventType": "NOTIFICATION_CREATED",
  "payload": {
    "notification_id": 123,
    "type": "NEW_MESSAGE",
    "title": "새 채팅",
    "content": "상대방님이 메시지를 보냈습니다",
    "body": "상대방님이 메시지를 보냈습니다",
    "target_type": "CHAT_ROOM",
    "target_id": 54,
    "travel_plan_id": null,
    "is_read": false,
    "created_at": "2026-11-01T10:00:00",
    "read_at": null
  }
}
```

봉투의 camelCase 필드명은 채팅과 동일하다. `payload`는 기존 `NotificationResponse` 형식을 사용한다.
브라우저에는 봉투 전체가 아닌 `payload`만 기존 SSE `notification` 이벤트로 전달하므로 FE 수신 형식은 유지된다.
알림 종류는 `payload.type`, 중복 식별자는 `payload.notification_id`다.
알 수 없는 이벤트 종류, 잘못된 수신자·알림 ID, 누락된 필수 payload는 로깅하고 버린다.

## SSE 이벤트 계약

인증된 사용자는 기존 `GET /api/notifications/subscribe`에 연결한다.

| 이벤트 | 데이터 | FE 처리 |
| --- | --- | --- |
| `connected` | `{"connected":true}` | 연결 수립 확인. 기존 계약 유지 |
| `resync_required` | `{"reason":"sse_connected"}` | 최초 연결·재연결 직후 알림 목록과 미읽음 개수 재조회 |
| `resync_required` | `{"reason":"redis_subscription_ready"}` | Redis 구독 복구 후 알림 목록과 미읽음 개수 재조회 |
| `notification` | 기존 `NotificationResponse` | `notification_id` 기준으로 실시간 알림 반영 |
| SSE comment | `:heartbeat` | 화면 상태를 변경하지 않는 연결 유지 신호 |

새 SSE 연결은 `connected` 다음에 `resync_required`를 보낸다.
Redis `SUBSCRIBE` 확인 응답은 `SubscriptionListener.onChannelSubscribed`로 감지한다.
이 콜백은 초기 구독과 재구독 모두에서 재동기화를 예약한다. Redis I/O 스레드에서는 SSE를 쓰지 않는다.
중복 예약은 합치고 다음 heartbeat 주기(기본 15초 간격)에 현재 로컬 연결 모두에 안내한다.
구독이 복구된 뒤 전송하므로 안내 후 DB 재조회가 복구 시점까지 저장된 알림을 포함할 수 있다.
연결 시점과 Redis 구독 시점의 안내가 겹칠 수 있으므로 FE는 재조회 요청을 병합해야 한다.

재조회 API는 `GET /api/notifications`, `GET /api/notifications/unread-count`다.
복구한 과거 알림을 모두 토스트로 다시 표시하지 않는다. 조회 중 실시간 이벤트가 들어오는 경우와
오래된 응답이 최신 상태를 덮어쓰는 경우는 FE에서 처리한다.
브라우저의 EventSource 자동 재연결만으로 DB 데이터가 재조회되는 것은 아니다.

## SSE 수명 및 인증

기본 heartbeat는 15초 간격, 연결 timeout은 30분이다. timeout은 연결의 총 수명이며 heartbeat로 연장되지 않는다.
timeout 후 브라우저가 재연결하면 새 `connected`·`resync_required`를 받아 DB 상태를 다시 조회한다.
heartbeat는 전용 `notificationSseScheduler`에서 실행하며 여행 생성 SQS 작업 스케줄러와 분리한다.
종료·timeout·오류·전송 실패 시 해당 연결만 제거한다. 한 탭 종료와 같은 사용자의 새 구독이 겹쳐도
다른 탭을 제거하지 않도록 사용자별 연결 추가·제거를 원자적으로 처리한다. BE 종료 시 열린 스트림을 완료한다.

기존 SSE는 최초 인증 후 비동기 종료 dispatch에서 인증 정보를 잃어
`AuthorizationDeniedException` 및 이미 커밋된 응답 관련 예외가 발생할 수 있었다.
`RequestAttributeSecurityContextRepository`에 최초 인증을 명시적으로 저장하여
같은 요청의 ASYNC/ERROR dispatch에서 복원한다. HTTP session은 생성하지 않는다.
새 요청에는 유효한 토큰이 계속 필요하며 SSE 경로를 익명 허용하지 않는다.

별개로 쿠키 누락·유효하지 않거나 만료된 Access Token에 의한 최초 연결 거절(현재 403)은 남아 있다.
FE는 필요한 경우 토큰 갱신 또는 재로그인 후 재구독하고, 로그아웃·계정 전환 시 기존 연결을 닫아야 한다.

## 설정

기존 Redis 연결·인증·TLS 설정과 ObjectMapper를 재사용한다. 추가 Redis 인스턴스는 필요하지 않다.

| 설정 | 기본값 / 의미 |
| --- | --- |
| `audigo.notification.realtime.application-name` | `${spring.application.name:audigo-be}` |
| `audigo.notification.realtime.environment` | `AUDIGO_ENVIRONMENT`, 없으면 `SPRING_PROFILES_ACTIVE`, 없으면 `local` |
| `AUDIGO_NOTIFICATION_REALTIME_CHANNEL` | 생략 시 `audigo:{앱이름}:{환경}:notification:realtime` |
| `AUDIGO_NOTIFICATION_SSE_TIMEOUT` | `30m`. 양의 밀리초 이상 |
| `AUDIGO_NOTIFICATION_SSE_HEARTBEAT_INTERVAL` | `15s`. 양의 밀리초 이상이며 timeout보다 짧아야 함 |

기본 로컬 채널은 `audigo:audigo-be:local:notification:realtime`이다.
같은 환경의 BE들은 동일한 Redis와 알림 채널을 사용해야 한다.
사용자 지정 채널도 `audigo:{앱이름}:{환경}:notification:` 접두사를 유지해야 한다.
채팅 채널과 개발·운영 채널은 분리한다. Redis DB 번호만 바꾸는 것으로 Pub/Sub 환경이 분리되지는 않는다.

## 저장 및 실패 처리

- #243의 채팅 알림 `REQUIRES_NEW`를 유지한다.
- 저장 롤백 시 Redis 이벤트를 발행하지 않는다.
- JSON 직렬화·Redis 발행·로컬 SSE 전송 실패는 저장된 알림을 취소하지 않는다.
- Redis 발행 실패 시 로컬 SSE로 우회하지 않는다. 우회와 구독 경로가 겹치는 상황을 만들지 않는다.
- 발행 로그의 `subscribers`는 Redis 구독 연결 수이며 브라우저 전달 성공 수가 아니다.
- Redis Pub/Sub은 이벤트를 보관·재생하지 않는다. 장애 중 놓친 알림은 DB 조회로 복구해야 한다.
- 재동기화 안내는 DB 재조회를 요청하는 제어 이벤트다. 놓친 알림 자체를 재생하는 기능은 아니다.
- DB 커밋 이후 발행 사이의 중단·개별 발행 실패에 대한 Outbox 및 발행 재시도는 이번 범위에 포함하지 않는다.
- BE 지원만으로 FE 복구가 완료되는 것은 아니다. FE 연동과 dev 통합 검증 후 이슈의 전체 완료 기준을 확인한다.

## 검증 구성

- 단위 테스트: 기존 채팅과 동일한 봉투, 알림 유형별 전달, 잘못된 이벤트 차단, 발행·전달 실패 격리.
- JPA/H2 트랜잭션 테스트: 커밋 후 실제 발행, 롤백 시 미발행, Redis 장애 시 DB 유지, 알림 설정 유지.
- Redis 컨테이너 통합 테스트: 독립된 두 연결·구독 컨테이너와 실제 SSE 서비스/MockMvc 응답을 사용한다.
  A·B 양쪽 발행, 대상 사용자의 여러 탭 수신, 다른 사용자 미수신을 검증한다.
- 위 통합 테스트는 실제 Redis와 SSE 처리를 검증하며, 전체 BE 프로세스 두 개·브라우저를 띄우는 수동 검증과는 구분한다.
- 구독 복구 테스트: 테스트 Redis의 Pub/Sub 연결만 `CLIENT KILL TYPE PUBSUB`으로 끊고,
  실제 재구독 확인·열린 SSE의 재동기화 안내·후속 알림 전달을 검사한다.
- SSE 관리 테스트: 초기 안내 순서, heartbeat, 다중 탭별 정리, timeout·오류·전송 실패·종료 처리를 검사한다.
- 스케줄링 테스트: 설정 바인딩 및 실제 전용 스케줄러의 heartbeat와 컨텍스트 종료를 검사한다.
- 보안 회귀 테스트: 기존 코드의 인증된 SSE 비동기 종료 실패를 재현했고, 수정 후 정상 종료·익명 차단을 확인한다.
  임베디드 HTTP 서버의 실제 SSE timeout 후에도 Access Denied가 기록되지 않는지 검사한다.

2026-11-02 기본 전달 수동 검증에서 실제 BE A(8080)·B(8081)의 양방향 전달을 확인했다.
A→B는 `NEW_MESSAGE` 알림 ID 6, B→A는 전송 응답 201 및 알림 ID 7 수신을 확인했다.
이 기록은 heartbeat·인증 보존 수정 이전 기본 전달 검증이며, 최종 dev 환경 검증은 별도로 진행한다.

2026-11-03 최종 자동 검증: Java 21에서 `gradlew.bat test bootJar --console=plain` 성공.
전체 테스트 322개 통과, 실패·오류·스킵 0개이며 실행 JAR 빌드도 성공했다.
최신 development의 채팅 미읽음 EXISTS 개선을 반영한 상태에서 검증했다.
테스트용 Redis와 임베디드 HTTP 서버를 사용했으며 실제 BE·기존 DB·`redis-local` 설정은 변경하지 않았다.

## 후속 로컬 검증 준비

전체 BE를 두 개 실행할 때는 `SERVER_PORT`만 각각 `8080`, `8081`로 나눈다.
Redis 연결 정보·인증, 환경과 알림 채널, DB 및 사용자 인증 설정은 공유한다.
기존 데이터가 있는 DB를 사용하며 `JPA_DDL_AUTO=validate`, `SPRING_SQL_INIT_MODE=never`로 설정하여
추가 BE 실행 시 스키마를 재생성하거나 초기 데이터를 다시 넣지 않도록 한다.

dev 환경에서는 로드밸런서·프록시의 SSE buffering·idle timeout, 여러 탭·사용자 격리,
서버 교체, Redis 연결 중단/복구를 검증한다.
FE의 목록·미읽음 개수 재조회 및 `notification_id` 기준 중복 처리 연동이 필요하다.

## 참고

- [Spring Security 인증 보존](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)
- [Redis 구독 확인 콜백](https://docs.spring.io/spring-data/redis/docs/current/api/org/springframework/data/redis/connection/SubscriptionListener.html)
- [Spring MVC 비동기/SSE 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
