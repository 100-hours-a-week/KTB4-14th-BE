-- Apply manually to the verified target database, with recording/worker disabled.
-- Local experiments use audigo_search_sync_lab; server rollout requires explicit target review.
-- Not included in schema.sql or automatic application initialization.
CREATE TABLE chat_message_search_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    text_contract VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id BIGINT NOT NULL,
    target_generation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_index VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    indexed_at DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    last_error_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_search_outbox_event (event_id),
    UNIQUE KEY uk_chat_search_outbox_target (message_id,event_type,target_generation),
    KEY idx_chat_search_outbox_pending (status,next_attempt_at,id),
    KEY idx_chat_search_outbox_lease (status,lease_until,id),
    CONSTRAINT chk_chat_search_outbox_status CHECK (status IN ('PENDING','PROCESSING','INDEXED','FAILED')),
    CONSTRAINT chk_chat_search_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_chat_search_outbox_payload CHECK (JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
