# 매칭 후보 응답 API

## 목적

AI 매칭 후보 목록에서 사용자가 마음에 드는 상대를 선택했을 때 BE에 선택 결과를 저장한다.
채팅방 기능은 후속 이슈에서 연결하므로, 이번 범위에서는 `match_connections` 생성까지만 담당한다.

## FE 호출 API

```http
POST /api/v2/matches/responses
Content-Type: application/json
```

요청:

```json
{
  "target_user_id": 22
}
```

성공 응답:

```json
{
  "message": "match_response_success",
  "data": {
    "match_connection_id": 30,
    "matching_request_id": 10,
    "target_user_id": 22,
    "status": "ACTIVE",
    "selected_at": "2026-10-10T15:30:00"
  }
}
```

## 처리 정책

- 현재 사용자의 매칭 요청이 없으면 `matching_request_not_found`를 반환한다.
- 존재하지 않는 상대 사용자면 `user_not_found`를 반환한다.
- 비활성 상대 사용자면 `user_inactive`를 반환한다.
- 자기 자신을 선택하면 `validation_failed`를 반환한다.
- 같은 매칭 요청에서 같은 상대를 여러 번 선택하면 기존 `ACTIVE` 연결을 반환한다.

## DB 저장

`match_connections`에 아래 값을 저장한다.

- `matching_request_id`: 현재 사용자의 매칭 요청 ID
- `target_user_id`: 후보 목록에서 선택한 상대 사용자 ID
- `selected_at`: 선택 시각
- `status`: `ACTIVE`

채팅방 생성은 후속 채팅 도메인 구현 시 `match_connection_id`를 기준으로 연결한다.
