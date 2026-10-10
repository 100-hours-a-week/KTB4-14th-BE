# AI 매칭 후보 조회 연동

## 목적

매칭 요청이 생성된 사용자가 후보 목록을 조회할 수 있도록 BE와 AI 서버 사이의 연동 경계를 정의한다.
AI FastAPI 구현이 완료되기 전에도 FE/BE 개발을 진행할 수 있도록 mock 모드를 제공한다.

## FE 호출 API

```http
GET /api/v2/matches/candidates
```

성공 응답:

```json
{
  "message": "get_match_candidates_success",
  "data": {
    "candidates": [
      {
        "user_id": 22,
        "nickname": "매칭후보",
        "profile_image_url": "https://example.com/profile.png",
        "theme": ["NATURE", "FOOD"],
        "pace": "BALANCED",
        "match_rate": 89
      }
    ],
    "count": 1
  }
}
```

현재 사용자의 매칭 요청이 없으면 `matching_request_not_found`를 반환한다.
AI 서버가 빈 후보 목록을 반환하면 `candidates: []`, `count: 0`으로 응답한다.

## BE -> AI 요청

BE는 현재 사용자의 `matching_requests`와 `matching_requests_themes` 데이터를 `request`에 담고,
나를 제외한 활성 매칭 프로필 목록을 `candidates`에 담아 AI 서버에 전달한다.

BE는 성별, 테마, 여행 속도, 예산 조건 기반의 후보 필터링을 수행하지 않는다.
현재 단계에서는 매칭 대상으로 활성화되어 있고 프로필 값이 완성된 타 사용자를 AI 서버로 넘기며,
최종 후보 3명 선별과 점수 계산은 AI 서버가 담당한다.

기본 AI endpoint:

```http
POST {AUDIGO_AI_BASE_URL}{AUDIGO_AI_MATCHING_CANDIDATES_PATH}
```

기본 path:

```text
/api/ai/v1/matches/candidates
```

요청 예시:

```json
{
  "request": {
    "matching_request_id": 10,
    "user_id": 1,
    "preferred_companion_gender": "FEMALE",
    "theme": ["NATURE", "FOOD"],
    "pace": "BALANCED",
    "budget_min": 100000,
    "budget_max": 800000
  },
  "candidates": [
    {
      "user_id": 22,
      "gender": "MALE",
      "theme": ["CULTURE", "SNS"],
      "pace": "RELAXED"
    },
    {
      "user_id": 23,
      "gender": "FEMALE",
      "theme": ["NATURE", "FOOD"],
      "pace": "BALANCED"
    }
  ]
}
```

AI 응답은 FE 응답의 `data`와 같은 구조를 기대한다.

```json
{
  "candidates": [
    {
      "user_id": 22,
      "nickname": "매칭후보",
      "profile_image_url": "https://example.com/profile.png",
      "theme": ["NATURE", "FOOD"],
      "pace": "BALANCED",
      "match_rate": 89
    }
  ],
  "count": 1
}
```

## 환경 변수

```text
AUDIGO_AI_BASE_URL=http://localhost:8000
AUDIGO_AI_MATCHING_CANDIDATES_PATH=/api/ai/v1/matches/candidates
AUDIGO_AI_MATCHING_MOCK_ENABLED=false
AUDIGO_AI_MATCHING_CANDIDATE_LIMIT=3
AUDIGO_API_TOKEN=
```

`AUDIGO_AI_MATCHING_MOCK_ENABLED=true`이면 실제 AI 서버를 호출하지 않고 BE에서 임시 후보를 생성한다.
운영 환경에서 가짜 후보가 노출되지 않도록 mock 기본값은 `false`다.

## 장애 처리

- 매칭 요청 없음: `matching_request_not_found`
- AI base URL 미설정: `service_unavailable`
- AI 서버 연결 실패: `service_unavailable`
- AI 서버 오류 응답: `external_api_error`

## 후속 작업

1. AI FastAPI의 실제 후보 추천 endpoint가 확정되면 `AUDIGO_AI_MATCHING_CANDIDATES_PATH`만 맞춘다.
2. 후보 목록에서 채팅방 연결을 시작하는 API가 구현되면 candidate의 `user_id`를 매칭 상대 ID로 사용한다.
3. 실제 추천 점수 산식이 확정되면 `match_rate` 계산 기준을 AI 서버 문서에 맞춰 고정한다.
