# 여행 확정 상태 컬럼 추가

운영 DB에 여행 확정 시간을 저장하기 위한 컬럼을 추가한다.

```sql
ALTER TABLE travel_plans
    ADD COLUMN confirmed_at DATETIME NULL AFTER status;
```
