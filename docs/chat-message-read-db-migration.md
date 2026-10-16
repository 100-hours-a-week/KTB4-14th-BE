# 채팅 읽음 기록 DB 마이그레이션

운영 DB의 `chat_message_reads`에는 사용자·메시지 조합이 중복되지 않도록 유니크 키가 필요하다. 기존 중복 행이 있으면 가장 이른 `read_at`을 가진 행을 남긴 뒤 인덱스를 교체한다. 아래 SQL은 MySQL 8.0 이상을 기준으로 하며, 운영 DB의 인덱스 상태를 확인한 후 한 번 적용한다.

```sql
DELETE duplicate
FROM chat_message_reads AS duplicate
JOIN chat_message_reads AS keeper
  ON keeper.user_id = duplicate.user_id
 AND keeper.message_id = duplicate.message_id
 AND (
      keeper.read_at < duplicate.read_at
      OR (keeper.read_at = duplicate.read_at AND keeper.id < duplicate.id)
 );

ALTER TABLE chat_message_reads
    DROP INDEX idx_chat_message_reads_user_message,
    ADD UNIQUE KEY uk_chat_message_reads_user_message (user_id, message_id);
```

새 DB는 `chat-room-list-db-migration.md`의 `chat_message_reads` 정의를 사용한다. 운영 배포 전에 이 변경을 적용해야 하며, 애플리케이션은 프로덕션의 Hibernate `ddl-auto: validate` 설정으로 스키마를 검사한다.
