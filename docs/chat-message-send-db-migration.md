# 채팅 메시지 전송 DB 마이그레이션

메시지 전송은 방별 순번, 요청 멱등성 키, 사용자별 최근 60초 전송 횟수를 DB에서 처리한다. 운영 DB에서는 애플리케이션 배포 전에 아래 변경을 적용한다. 기존 메시지는 방별 `sent_at`, `id` 순서로 `message_seq`를 부여하고, 이전 요청에는 UUID를 생성해 멱등성 키를 채운다.

아래 백필 SQL은 MySQL 8.0 이상을 기준으로 한다. 실제 컬럼·인덱스 상태를 먼저 확인하고 이미 적용된 변경은 생략한다.

```sql
ALTER TABLE chat_rooms
    ADD COLUMN last_message_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_messages
    ADD COLUMN client_message_id VARCHAR(36) NULL,
    ADD COLUMN message_seq BIGINT NULL;

UPDATE chat_messages
SET client_message_id = UUID()
WHERE client_message_id IS NULL;

UPDATE chat_messages AS target
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY chat_room_id ORDER BY sent_at, id) AS sequence_number
    FROM chat_messages
) AS numbered ON numbered.id = target.id
SET target.message_seq = numbered.sequence_number;

UPDATE chat_rooms AS room
LEFT JOIN (
    SELECT chat_room_id, MAX(message_seq) AS last_sequence
    FROM chat_messages
    GROUP BY chat_room_id
) AS latest ON latest.chat_room_id = room.id
SET room.last_message_sequence = COALESCE(latest.last_sequence, 0);

ALTER TABLE chat_messages
    MODIFY COLUMN client_message_id VARCHAR(36) NOT NULL,
    MODIFY COLUMN message_seq BIGINT NOT NULL,
    ADD UNIQUE KEY uk_chat_messages_room_message_seq (chat_room_id, message_seq),
    ADD KEY idx_chat_messages_sender_status_sent_at (sender_id, status, sent_at),
    ADD UNIQUE KEY uk_chat_messages_room_sender_client_id (chat_room_id, sender_id, client_message_id);
```

## 롤아웃 순서

1. 백업 또는 복구 지점을 준비한다.
2. 운영 DB 스키마를 확인한 뒤 위 DDL을 적용한다.
3. Hibernate `ddl-auto: validate`를 사용하는 배포 환경에서 애플리케이션을 올린다.
4. 롤백이 필요하면 애플리케이션을 이전 버전으로 되돌린다. 새 컬럼을 제거하는 작업은 새 메시지 데이터가 생길 수 있으므로 별도 판단 없이 수행하지 않는다.
