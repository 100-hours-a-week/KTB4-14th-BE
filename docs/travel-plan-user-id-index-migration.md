# 여행 목록 조회 사용자 ID 인덱스 추가

`GET /api/travel-plans/me`의 `WHERE user_id = ?` 조건을 지원하기 위해
`travel_plans.user_id`에 단일 비고유 인덱스를 추가한다.

운영 DB에서는 엔티티의 `@Index` 선언만으로 인덱스가 생성된다고 가정하지 않는다.
먼저 실제 인덱스를 확인하고, `user_id`가 선두 컬럼인 사용 가능한 인덱스가 없다면
아래 DDL을 별도로 적용한다. 같은 이름의 인덱스가 있다면 컬럼 구성을 확인한다.

```sql
SHOW INDEX FROM travel_plans;

CREATE INDEX idx_travel_plans_user_id
    ON travel_plans (user_id);
```

`created_at`은 인덱스에 포함하지 않는다. 목록의 기존 `created_at DESC` 정렬과
전체 목록 반환 동작은 유지한다.

## 검증

- 적용 전후 실제 목록 조회 SQL의 실행 계획을 비교해 인덱스 사용 여부와 조회 행 수를 확인한다.
- 동일한 데이터와 사용자 분포로 200 RPS 부하 테스트를 수행하고 평균·p95 응답 시간,
  에러율, DB CPU 및 커넥션 풀 대기를 비교한다.
- 응답 시간 개선 폭은 실제 DB 적용 및 부하 테스트 후 기록한다.

## 롤백

이번 변경으로 생성한 인덱스를 제거할 경우 아래 DDL을 적용한다.

```sql
DROP INDEX idx_travel_plans_user_id ON travel_plans;
```
