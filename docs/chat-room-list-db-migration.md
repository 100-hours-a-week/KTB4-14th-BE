# 채팅방 목록 조회 DB 스키마

운영 환경은 Hibernate `ddl-auto: validate`를 사용하므로, 채팅 테이블이 아직 없다면 애플리케이션 배포 전에 아래 DDL을 적용한다. 테이블이 이미 존재하면 실제 스키마를 먼저 확인하고 필요한 변경만 적용한다.

```sql
CREATE TABLE chat_rooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    match_connection_id BIGINT NOT NULL,
    chat_name VARCHAR(20) NOT NULL,
    status ENUM('ACTIVE', 'ENDED') NOT NULL,
    created_at DATETIME NOT NULL,
    last_message_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_rooms_match_connection (match_connection_id),
    KEY idx_chat_rooms_last_message (last_message_at, id),
    CONSTRAINT fk_chat_rooms_match_connection
        FOREIGN KEY (match_connection_id) REFERENCES match_connections (id)
) ENGINE=InnoDB;

CREATE TABLE chat_room_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chat_room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_chat_room_members_room_user (chat_room_id, user_id),
    KEY idx_chat_room_members_user_deleted (user_id, deleted_at),
    CONSTRAINT fk_chat_room_members_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id),
    CONSTRAINT fk_chat_room_members_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chat_room_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    status ENUM('SENT', 'FAILED') NOT NULL,
    sent_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_messages_room_status_sent_at (chat_room_id, status, sent_at),
    CONSTRAINT fk_chat_messages_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id),
    CONSTRAINT fk_chat_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE chat_message_reads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_message_reads_user_message (user_id, message_id),
    CONSTRAINT fk_chat_message_reads_message
        FOREIGN KEY (message_id) REFERENCES chat_messages (id),
    CONSTRAINT fk_chat_message_reads_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB;
```
