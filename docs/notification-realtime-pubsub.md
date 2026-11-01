# Redis Pub/Sub 알림 전달: 1단계

이슈: #245. 이번 단계는 기본 전달 경로 구현이며, heartbeat·재구독 안내 및 FE 재동기화는 후속 단계다.

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

## 설정

기존 Redis 연결·인증·TLS 설정과 ObjectMapper를 재사용한다. 추가 Redis 인스턴스는 필요하지 않다.

| 설정 | 기본값 / 의미 |
| --- | --- |
| `audigo.notification.realtime.application-name` | `${spring.application.name:audigo-be}` |
| `audigo.notification.realtime.environment` | `AUDIGO_ENVIRONMENT`, 없으면 `SPRING_PROFILES_ACTIVE`, 없으면 `local` |
| `AUDIGO_NOTIFICATION_REALTIME_CHANNEL` | 생략 시 `audigo:{앱이름}:{환경}:notification:realtime` |

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
- 이 단계에는 자동 재동기화 안내·heartbeat·FE 중복 처리가 포함되지 않는다. #245 전체 완료로 간주하지 않는다.

## 검증 구성

- 단위 테스트: 기존 채팅과 동일한 봉투, 알림 유형별 전달, 잘못된 이벤트 차단, 발행·전달 실패 격리.
- JPA/H2 트랜잭션 테스트: 커밋 후 실제 발행, 롤백 시 미발행, Redis 장애 시 DB 유지, 알림 설정 유지.
- Redis 컨테이너 통합 테스트: 독립된 두 연결·구독 컨테이너와 실제 SSE 서비스/MockMvc 응답을 사용한다.
  A·B 양쪽 발행, 대상 사용자의 여러 탭 수신, 다른 사용자 미수신을 검증한다.
- 위 통합 테스트는 실제 Redis와 SSE 처리를 검증하며, 전체 BE 프로세스 두 개·브라우저를 띄우는 수동 검증과는 구분한다.

2026-11-01 검증 결과: Java 21에서 `gradlew.bat test bootJar --console=plain` 성공.
전체 테스트 304개 통과, 실패·오류·스킵 0개. 기존 채팅 Redis 테스트도 통과했다.
전체 테스트의 health 검사에는 별도 임시 Redis를 연결하고 완료 후 정리했다.
사용자가 실행 중인 `redis-local`과 데이터는 변경하지 않았다.

## 후속 로컬 검증 준비

전체 BE를 두 개 실행할 때는 `SERVER_PORT`만 각각 `8080`, `8081`로 나눈다.
Redis 연결 정보·인증, 환경과 알림 채널, DB 및 사용자 인증 설정은 공유한다.
기존 데이터가 있는 DB를 사용하며 `JPA_DDL_AUTO=validate`, `SPRING_SQL_INIT_MODE=never`로 설정하여
추가 BE 실행 시 스키마를 재생성하거나 초기 데이터를 다시 넣지 않도록 한다.

후속 단계에서 SSE 연결 완료·Redis 재구독 완료 시 재동기화 안내 계약을 정의하고,
FE의 목록·미읽음 개수 재조회 및 `notification_id` 기준 중복 처리를 연동한다.
