# 매칭 요청 세션 전환 DB 마이그레이션

운영 환경은 Hibernate `ddl-auto: validate`를 사용하므로 애플리케이션 배포 전에 아래 DDL을 적용한다.

```sql
ALTER TABLE matching_requests
    ADD COLUMN status ENUM('ACTIVE', 'CONNECTED', 'EXHAUSTED', 'CANCELLED') NULL AFTER budget_max,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN ended_at DATETIME NULL AFTER retry_count;

UPDATE matching_requests AS request
LEFT JOIN (
    SELECT matching_request_id, MAX(selected_at) AS connected_at
    FROM match_connections
    GROUP BY matching_request_id
) AS connection ON connection.matching_request_id = request.id
SET request.status = CASE
        WHEN connection.matching_request_id IS NOT NULL THEN 'CONNECTED'
        ELSE 'ACTIVE'
    END,
    request.ended_at = connection.connected_at;

ALTER TABLE matching_requests
    MODIFY COLUMN status ENUM('ACTIVE', 'CONNECTED', 'EXHAUSTED', 'CANCELLED') NOT NULL;

CREATE INDEX idx_matching_requests_requester_status
    ON matching_requests (requester_id, status);
```

연결 이력이 있는 기존 요청은 `CONNECTED`로 종료하고, 연결되지 않은 기존 요청만 `ACTIVE`로 유지한다. 롤백이 필요하면 인덱스를 먼저 제거한 뒤 추가된 세 컬럼을 삭제한다.
