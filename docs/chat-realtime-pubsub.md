# 채팅 실시간 이벤트 서버 간 전달

## 목적과 구성

HTTP 메시지 전송과 DB 저장 방식은 그대로 사용한다. 각 백엔드의 Spring Simple Broker도 해당 서버에 연결된 WebSocket 세션의 구독 정보를 계속 관리한다. Redis Pub/Sub은 서버 사이에 이벤트를 알리는 통로다. WebSocket 연결이나 구독 정보를 Redis에 저장하지 않는다.

온라인 상태 저장소의 `chat:presence:user:<사용자 ID>` 키는 접속 여부를 조회할 때 사용한다. 채팅 이벤트는 별도의 Pub/Sub 채널 `audigo:<애플리케이션>:<환경>:chat:realtime`로 보낸다. 같은 Redis를 사용하더라도 온라인 상태 키와 이벤트 채널은 서로 다른 기능이다.

## 이벤트가 전달되는 순서

1. 프런트엔드가 기존 HTTP API로 메시지를 보낸다.
2. 요청을 받은 서버가 메시지와 관련 도메인 데이터를 DB 트랜잭션으로 저장한다.
3. DB 커밋 뒤 `ChatRealtimePublisher`가 JSON 이벤트를 Redis 채널에 발행한다.
4. 같은 채널을 구독하는 모든 백엔드가 이벤트를 받는다. 발행한 서버도 받는다.
5. 각 서버의 `RedisChatRealtimeListener`가 허용된 이벤트 종류를 읽고 `ChatLocalRealtimeDispatcher`에 넘긴다.
6. 각 서버는 `convertAndSendToUser(...)`로 자기 서버에 연결된 해당 사용자 세션에 전달한다. 연결된 세션이 없는 서버에서는 클라이언트에게 도착하지 않는다.
7. 프런트엔드는 기존 `/user/queue/chat/messages` 구독에서 원래 JSON 형식의 이벤트를 받는다.

발행 서버 역시 Redis 구독 결과로 한 번 전달한다. 서비스에서 Simple Broker로 직접 보내는 경로는 제거했으므로 같은 서버에서 중복 전송하지 않는다. 한 사용자가 A와 B에 모두 연결되어 있으면 두 서버의 세션이 각각 받는다. Redis 수신기는 받은 이벤트를 재발행하지 않는다.

## Redis 채널과 JSON 형식

기본 채널은 `audigo:audigo-be:local:chat:realtime`이다. `audigo.chat.realtime.application-name`과 `audigo.chat.realtime.environment`로 앱과 환경을 구분한다. 두 서버가 서로 이벤트를 주고받으려면 이 두 값과 Redis 연결 정보가 같아야 한다. 운영 환경은 `AUDIGO_ENVIRONMENT` 또는 `SPRING_PROFILES_ACTIVE`로 별도 환경명을 지정한다. `AUDIGO_CHAT_REALTIME_CHANNEL`을 직접 지정할 때에도 `audigo:<애플리케이션>:<환경>:` 접두사를 사용해야 한다.

Redis에는 다음과 같은 UTF-8 JSON 문자열을 발행한다.

```json
{
  "recipientUserId": 42,
  "eventType": "MESSAGE_CREATED",
  "payload": {
    "event_type": "MESSAGE_CREATED",
    "data": {
      "message_id": 901,
      "room_id": 15,
      "sender_id": 8,
      "client_message_id": "00000000-0000-0000-0000-000000000001",
      "message_seq": 21,
      "content": "안녕하세요",
      "sent_at": "2026-10-25T02:00:00Z"
    }
  }
}
```

`recipientUserId`는 서버가 수신 세션을 찾는 데 쓰고, `eventType`은 해석할 이벤트 종류를 정한다. `payload`가 기존 클라이언트가 받던 JSON 본문이다. 수신기는 `MESSAGE_CREATED`, `READ_RECEIPT_UPDATED`, `PRESENCE_UPDATED`만 각각 정해진 DTO로 읽는다. 임의의 Java 클래스 이름을 이벤트에 담거나 Java 객체 직렬화를 사용하지 않는다.

## 이벤트별 발행 시점

| 이벤트 | 발행 시점 | 수신자 |
|---|---|---|
| `MESSAGE_CREATED` | 새 메시지 DB 커밋 뒤 | 채팅 상대 |
| `READ_RECEIPT_UPDATED` | 읽음 상태 DB 커밋 뒤 | 채팅 상대 |
| `PRESENCE_UPDATED` | 접속 상태 변화가 확인된 뒤 | 활성 매칭 상대들 |

메시지 재요청에서 기존 멱등 키의 메시지를 반환할 때에는 새 이벤트를 발행하지 않는다. 새 메시지 알림을 생성하는 기존 조건도 유지한다. 읽음 이벤트의 활성 방 및 상대 확인 조건을 유지한다. 접속 이벤트는 기존 온라인 상태 판단을 따른다.

현재 접속 관리자는 인스턴스마다 첫 로컬 세션 연결을 온라인 변화로 판단한다. 같은 사용자가 A와 B에 차례로 연결하면 `PRESENCE_UPDATED` 온라인 이벤트가 두 번 발행될 수 있다. 수신 측에서는 최신 온라인 상태를 표시하고, 재연결 시 Redis 기반 상태 조회로 다시 맞춘다.

## 실패와 유실

Redis 발행 실패는 `chat_realtime_redis_publish_failed` 로그로 기록한다. 이미 커밋된 메시지나 읽음 상태는 되돌리지 않고 HTTP 요청도 실패시키지 않는다. 로컬 전달 또는 잘못된 이벤트 수신도 식별 가능한 로그로 남긴다.

Redis Pub/Sub은 이벤트를 보관하지 않는다. 서버가 구독 중이지 않거나 Redis 연결이 끊긴 동안 발행된 이벤트는 나중에 자동으로 재전달되지 않는다. 메시지는 DB에 남아 있으므로 프런트엔드가 STOMP 재연결 후 `GET /api/v2/chat/rooms/{roomId}/messages?after_message_seq=<마지막 시퀀스>&size=<크기>`를 호출해 빠진 메시지를 조회한다. 이어진 메시지 시퀀스에 빈 구간이 보여도 이 API를 호출한다. 읽음과 접속 이벤트의 이력은 이 API로 복구하지 않으며, 재연결 시 방 상세 조회로 최신 상태를 다시 확인한다.

WebSocket 연결은 유지된 채 마지막 실시간 이벤트 하나만 유실되고 뒤따르는 메시지도 없다면 프런트엔드가 즉시 유실을 알아차릴 수 없다. 다음 재연결 또는 화면 재조회 때 DB 상태와 맞춘다. 모든 이벤트의 유실 없는 전달이나 재생이 필요해지면 Redis Streams 또는 Outbox를 검토할 수 있다. 외부 STOMP 브로커 도입은 현재 구성에 포함하지 않는다.

## 백엔드 두 대로 수동 확인

1. 로컬 MySQL을 그대로 사용하고 `docker compose up -d redis`로 Redis만 실행할 수 있다. 두 백엔드를 같은 MySQL과 Redis에 연결하고, `AUDIGO_ENVIRONMENT`를 같은 값으로 설정한다. 백엔드 A는 8080, B는 8081 포트로 실행한다. `AUDIGO_CORS_ALLOWED_ORIGINS`에는 확인에 사용할 두 프런트엔드 주소를 설정한다.
2. 프런트엔드 A는 `VITE_API_BASE_URL=http://localhost:8080`, 프런트엔드 B는 `VITE_API_BASE_URL=http://localhost:8081`로 각각 실행한다. 로그인 쿠키가 섞이지 않도록 다른 브라우저 프로필을 사용한다.
3. 보낸 사람으로 A에 로그인하고 받는 사람으로 B에 로그인한다. 두 브라우저에서 같은 채팅방을 연 뒤 A에서 메시지를 보낸다. B 화면에서 새 메시지가 새로고침 없이 나타나는지 확인한다.
4. B에서 메시지를 읽고 A에 읽음 표시가 바뀌는지 확인한다. B의 WebSocket 연결을 끊거나 다시 연결해 A에 접속 상태 변화가 보이는지 확인한다.
5. 같은 사용자로 A와 B에 동시에 연결한 뒤 그 사용자에게 새 메시지를 보내 두 세션 모두 이벤트를 받는지 확인한다.
6. Redis 연결이 끊긴 동안 만든 메시지는 실시간으로 전달되지 않을 수 있다. 연결을 복구하고 B의 WebSocket을 다시 연결하면 프런트엔드의 catch-up 조회로 DB 메시지가 나타나는지 확인한다.

채널을 확인할 때 `redis-cli SUBSCRIBE audigo:audigo-be:local:chat:realtime`을 사용할 수 있다. 이 명령은 이벤트를 보는 용도이며 서버의 WebSocket 구독을 대신하지 않는다.

## 로컬 검증 결과 (2026-10-25)

- 호스트에서 실행 중인 MySQL과 Docker Redis 하나에 백엔드 A(8080), B(8081)를 연결했다. 기존 로컬 DB를 변경하지 않도록 시작 시 `JPA_DDL_AUTO=validate`, `SPRING_SQL_INIT_MODE=never`를 사용했다.
- 두 백엔드의 헬스 체크가 `UP`이었고 Redis 채널의 구독 수가 2였다.
- 임시 사용자 ID의 인증된 STOMP 세션을 B에만 연결한 경우, Redis에 발행한 `MESSAGE_CREATED`, `READ_RECEIPT_UPDATED`, `PRESENCE_UPDATED`가 B의 `/user/queue/chat/messages`로 도착했다.
- 같은 임시 사용자 ID를 A와 B에 모두 연결한 경우, 세 이벤트가 각 서버의 세션에 도착했고 클라이언트 JSON 본문이 유지됐다.
- 실제 Redis를 사용하는 통합 테스트에서는 `ChatRealtimePublisher`가 발행한 이벤트를 구독기 두 개가 받았다. 서비스 테스트에서는 메시지와 읽음 이벤트의 커밋 이후 발행을 확인했다.
- 기존 `audigo` 스키마와 분리된 임시 MySQL 스키마에 검증용 사용자 둘과 채팅방 하나를 만들었다. A에 HTTP로 메시지를 전송했을 때 DB 커밋 후 B의 WebSocket에서 `MESSAGE_CREATED`를 받았고, B의 catch-up API에서도 저장된 메시지를 확인했다.
- B에서 HTTP 읽음 처리를 한 뒤 A의 WebSocket에서 `READ_RECEIPT_UPDATED`를 받았다. B의 WebSocket 연결과 해제에 따른 `PRESENCE_UPDATED` 온라인·오프라인 이벤트도 A에서 받았다.
- 검증이 끝난 뒤 임시 백엔드와 Redis를 중지하고 임시 MySQL 스키마를 삭제했다. 기존 `audigo` 스키마에는 시험 메시지를 남기지 않았다.
