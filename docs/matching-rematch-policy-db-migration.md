# 재매칭 시도 이력 DB 마이그레이션

운영 환경은 Hibernate `ddl-auto: validate`를 사용하므로 애플리케이션 배포 전에 아래 DDL을 적용한다.

```sql
CREATE TABLE matching_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    matching_request_id BIGINT NOT NULL,
    retry_sequence INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(500) NULL,
    started_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_matching_attempt_request_status (matching_request_id, status),
    CONSTRAINT fk_matching_attempt_request
        FOREIGN KEY (matching_request_id) REFERENCES matching_requests (id)
);

CREATE TABLE matching_attempt_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    matching_attempt_id BIGINT NOT NULL,
    candidate_user_id BIGINT NOT NULL,
    match_rate INT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_matching_attempt_candidate
        UNIQUE (matching_attempt_id, candidate_user_id),
    CONSTRAINT fk_matching_attempt_candidate_attempt
        FOREIGN KEY (matching_attempt_id) REFERENCES matching_attempts (id),
    CONSTRAINT fk_matching_attempt_candidate_user
        FOREIGN KEY (candidate_user_id) REFERENCES users (id)
);
```

`retry_sequence`는 최초 추천이면 `0`, 성공한 재매칭이면 `1`부터 `3`까지 사용한다. 실패한 시도는 `FAILED`로 남고 `matching_requests.retry_count`를 증가시키지 않는다.

재매칭 API는 `POST /api/v2/matches/candidates/rematch`이며 응답에 `retry_count`, `remaining_retry_count`, `can_rematch`가 포함된다.
