# 여행 생성 작업 영속화 DB 마이그레이션

운영 환경은 Hibernate `ddl-auto: validate`를 사용하므로 애플리케이션 배포 전에 아래 스키마 변경이 필요하다. 현재 진행 중인 운영 DB 마이그레이션이 끝난 뒤 실제 스키마와 인덱스를 확인하고 적용한다.

요청·결과 payload는 상태 조회 시 큰 JSON을 함께 읽지 않도록 1:1 별도 테이블에 저장한다. `execution_status`는 사용자에게 노출하는 여행 상태와 별개로 큐 대기와 실제 실행을 구분한다. `started_at`은 worker가 실제 처리를 시작한 시각만 기록하므로 nullable로 변경한다.

```sql
ALTER TABLE ai_generation_jobs
    ADD COLUMN execution_status VARCHAR(30) NULL;

UPDATE ai_generation_jobs
SET execution_status = CASE
    WHEN status = 'COMPLETED' THEN 'COMPLETED'
    WHEN status = 'FAILED' THEN 'FAILED'
    WHEN started_at IS NULL THEN 'QUEUED'
    ELSE 'PROCESSING'
END
WHERE execution_status IS NULL;

ALTER TABLE ai_generation_jobs
    MODIFY COLUMN execution_status VARCHAR(30) NOT NULL,
    MODIFY COLUMN started_at DATETIME NULL;

CREATE TABLE ai_generation_job_payloads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_job_id BIGINT NOT NULL,
    request_payload LONGTEXT NOT NULL,
    result_payload LONGTEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_generation_job_payloads_job (generation_job_id),
    CONSTRAINT fk_ai_generation_job_payloads_job
        FOREIGN KEY (generation_job_id) REFERENCES ai_generation_jobs (id)
) ENGINE=InnoDB;

CREATE TABLE ai_generation_job_progress (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_job_id BIGINT NOT NULL,
    stage VARCHAR(30) NOT NULL,
    state VARCHAR(20) NOT NULL,
    payload LONGTEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_generation_job_progress_job_stage (generation_job_id, stage),
    CONSTRAINT fk_ai_generation_job_progress_job
        FOREIGN KEY (generation_job_id) REFERENCES ai_generation_jobs (id)
) ENGINE=InnoDB;
```

롤백 시에는 애플리케이션을 이전 버전으로 먼저 되돌린 뒤 추가 컬럼을 제거한다. `started_at`을 다시 `NOT NULL`로 바꾸기 전에 `NULL` 값을 `created_at`으로 보정해야 한다.

```sql
UPDATE ai_generation_jobs
SET started_at = created_at
WHERE started_at IS NULL;

DROP TABLE ai_generation_job_progress;
DROP TABLE ai_generation_job_payloads;

ALTER TABLE ai_generation_jobs
    MODIFY COLUMN started_at DATETIME NOT NULL,
    DROP COLUMN execution_status;
```
