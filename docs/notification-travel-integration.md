# Notification Travel Integration

## V1 scope

- Travel itinerary generation completion and failure send notifications to the requesting user.
- Chat and matching notification entry points remain prepared, but are not connected in V1.
- SSE is used only for realtime delivery after the notification is persisted.

## Unified API fields

Notification API responses use these frontend-facing fields:

```json
{
  "notification_id": 1,
  "type": "TRAVEL_COMPLETE",
  "title": "여행 추천 완료",
  "content": "서울특별시 여행 일정이 완성됐어요.",
  "body": "서울특별시 여행 일정이 완성됐어요.",
  "target_type": "TRAVEL_PLAN",
  "target_id": 55,
  "travel_plan_id": 55,
  "is_read": false,
  "created_at": "2026-09-23T12:00:00",
  "read_at": null
}
```

| Field | Meaning |
| --- | --- |
| `notification_id` | Notification row id |
| `type` | Notification type enum |
| `body` | Text shown in the frontend list/toast |
| `target_type` | Click target domain |
| `target_id` | Target domain id |
| `travel_plan_id` | Present only when `target_type` is `TRAVEL_PLAN` |
| `is_read` | Read state |
| `created_at` | Created time |
| `read_at` | Read time, nullable |

## Notification types

| Type | V1 usage |
| --- | --- |
| `TRAVEL_COMPLETE` | Sent when AI itinerary generation succeeds |
| `TRAVEL_FAILED` | Sent when AI itinerary generation fails |
| `TRAVEL_BEFORE` | Prepared for D-1 reminders |
| `NEW_MESSAGE` | Prepared for chat |
| `MATCH_SUCCESS` | Prepared for matching |

## Flow

1. Frontend requests travel generation with `POST /api/travel-plans`.
2. Backend creates `travel_plans` and `ai_generation_jobs`.
3. AI generation worker processes SSE events from the AI server.
4. `TravelGenerationJobService.completeIfReady()` marks the job and plan as completed.
5. The same service calls `NotificationService.notifyTravelComplete(...)`.
6. `NotificationService` stores the notification in `notifications`.
7. `NotificationSseService` emits an SSE event named `notification`.
8. Frontend `NotificationProvider` receives the event, increases unread count, and shows a toast.

Failure uses the same path through `TravelGenerationJobService.markFailed()` and `NotificationService.notifyTravelFailed(...)`.

## Internal validation and logs

`CreateNotificationCommand` validates notification creation inputs before DB access.

| Field | Validation |
| --- | --- |
| `userId` | Required, positive |
| `notificationType` | Required |
| `title` | Required, not blank |
| `content` | Required, not blank |
| `targetType` | Required |
| `targetId` | Required, positive |

Invalid commands throw `invalid_notification_request`.

Log examples:

```text
notification_command_invalid field=user_id reason=null
notification_command_invalid field=target_id reason=not_positive value=0
```

## SSE endpoint

```http
GET /api/notifications/subscribe
Accept: text/event-stream
Cookie: audigo_access_token={ACCESS_TOKEN}
```

SSE event:

```text
event: notification
data: { ...NotificationResponse }
```

Because authentication is cookie-based, the frontend opens `EventSource` with `withCredentials: true`.

## Settings

Frontend uses the backend setting fields directly:

```json
{
  "match_success_enabled": true,
  "chat_enabled": true,
  "travel_before_enabled": true,
  "travel_complete_enabled": true,
  "notification_enabled": true
}
```

`TRAVEL_COMPLETE` and `TRAVEL_FAILED` are both controlled by `travel_complete_enabled` in the current ERD.
