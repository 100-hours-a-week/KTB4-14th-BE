# 매칭 연결 및 채팅방 연결 통로

## 목적

매칭 후보 응답으로 생성된 `match_connection_id`를 기준으로 채팅방 이동에 필요한 `chat_room_id`를 준비한다.
실제 채팅 메시지 송수신 기능은 후속 채팅 이슈에서 구현하며, 이번 범위에서는 채팅방과 참여 멤버 데이터까지만 생성한다.

## FE 호출 API

```http
POST /api/v2/match-connections/{matchConnectionId}/chat-room
```

성공 응답:

```json
{
  "message": "match_chat_room_ready",
  "data": {
    "chat_room_id": 40,
    "match_connection_id": 30,
    "chat_name": "윤혁 · 매칭후보",
    "status": "ACTIVE",
    "member_user_ids": [1, 22],
    "created_at": "2026-10-10T15:30:00"
  }
}
```

## 처리 정책

- 현재 사용자가 매칭 연결의 요청자 또는 상대 사용자일 때만 접근할 수 있다.
- 매칭 연결이 없으면 `match_connection_not_found`를 반환한다.
- 매칭 연결 참여자가 아니면 `forbidden`을 반환한다.
- 매칭 연결 상태가 `ACTIVE`가 아니면 `validation_failed`를 반환한다.
- 이미 채팅방이 있으면 새로 만들지 않고 기존 채팅방을 반환한다.

## DB 저장

`chat_rooms`

- `match_connection_id`: 매칭 연결 ID, 1:1 unique
- `chat_name`: 임시 채팅방 이름
- `status`: `ACTIVE`
- `created_at`: 생성 시각

`chat_room_members`

- `chat_room_id`: 채팅방 ID
- `user_id`: 요청자와 상대 사용자
- `joined_at`: 참여 시각
- `deleted_at`: 나가기 기능에서 사용 예정

## 후속 작업

1. 채팅 메시지 저장 테이블과 WebSocket 연결을 구현한다.
2. 채팅방 목록 API에서 `chat_room_members` 기준으로 사용자의 채팅방을 조회한다.
3. 메시지 전송 시 `chat_room_id`를 기준으로 `NEW_MESSAGE` 알림을 연결한다.
