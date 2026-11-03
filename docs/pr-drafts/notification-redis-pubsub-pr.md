PR 제목: [Feat/#245] Redis Pub/Sub 알림 전달 및 SSE 연결 복구 지원

Base: development
Compare: feat/245-notification-redis-pubsub

---

### 📌 관련 이슈번호

- Refs #245

### 📌 PR 유형

- [x] 새 기능 추가
- [x] 버그 수정
- [ ] 리팩토링

### 📌 PR 요약

알림 생성 서버와 사용자의 SSE 연결 서버가 달라도 실시간 알림을 전달하도록 Redis Pub/Sub 경로를 추가했습니다. 알림은 DB 커밋 이후 발행하고, 각 서버는 구독한 이벤트를 대상 사용자의 로컬 SSE 연결로 전달합니다.

SSE 연결·Redis 재구독 이후 FE가 저장된 알림 상태를 복구할 수 있도록 재동기화 안내 이벤트를 제공하고, SSE 비동기 종료 과정에서 발생하던 `Access Denied`도 수정했습니다.

### 📌 작업 세부 내용

1. **Redis 알림 전달**
   - 기존 채팅과 동일한 `recipientUserId / eventType / payload` 형식을 사용합니다.
   - 앱·환경별 알림 전용 채널을 분리하고 기존 Redis 연결·인증 설정을 재사용합니다.
   - 여행 완료·실패, 매칭 성공, 새 채팅 알림의 공통 생성 경로에서 커밋 후 발행합니다.
   - 발행 서버도 구독 경로로 전달하도록 통일하고, 수신 이벤트를 재발행하지 않습니다.
2. **SSE 연결 유지·정리**
   - 전용 스케줄러에서 기본 15초마다 heartbeat comment를 전송합니다.
   - 연결 종료·timeout·오류·전송 실패 시 해당 탭만 정리하며, BE 종료 시 열린 스트림을 완료합니다.
   - 같은 사용자의 연결 추가·제거가 겹쳐도 다른 탭이 제거되지 않도록 처리합니다.
3. **복구를 위한 이벤트 계약**
   - 새 연결에서 `connected` 다음 `resync_required`를 전송합니다.
   - Redis 구독 확인 콜백에서 재동기화를 예약하고, 다음 heartbeat 주기에 기존 연결에 안내합니다. 중복 예약은 병합합니다.
   - 기존 SSE `notification` payload와 목록·미읽음 개수 API는 유지합니다.
4. **SSE 종료 인증 오류 수정**
   - 최초 인증 정보를 요청 속성에 저장하여 같은 요청의 ASYNC/ERROR dispatch에서 복원합니다.
   - HTTP session을 생성하지 않으며, 인증되지 않은 새 SSE 요청은 계속 차단합니다.
5. **실패 처리 및 설정**
   - Redis·SSE 전송 실패가 저장된 알림을 취소하지 않도록 유지하고 잘못된 이벤트를 차단합니다.
   - `AUDIGO_NOTIFICATION_REALTIME_CHANNEL`, `AUDIGO_NOTIFICATION_SSE_TIMEOUT`(기본 `30m`), `AUDIGO_NOTIFICATION_SSE_HEARTBEAT_INTERVAL`(기본 `15s`)을 지원합니다.

### ✅ 검증

- 2026-11-03 Java 21에서 `gradlew.bat test bootJar --console=plain` 성공: 전체 테스트 **322개 통과**, 실패·오류·스킵 0개, 실행 JAR 빌드 성공.
- 실제 Redis를 사용하는 두 서버 역할의 구독 테스트로 양방향 전달, 여러 탭 수신 및 사용자 격리를 확인했습니다.
- 테스트 Redis의 Pub/Sub 연결을 강제로 끊은 후 재구독 확인, 열린 SSE의 재동기화 안내 및 후속 알림 전달을 확인했습니다.
- JPA/H2 테스트로 커밋 전 미발행, 롤백 시 미발행, Redis 장애 시 알림 저장 유지와 기존 알림 설정을 검증했습니다.
- 기존 코드에서 SSE 비동기 종료 실패를 재현하고, 수정 후 정상 종료·익명 접근 차단·실제 HTTP SSE timeout을 검증했습니다.
- 기본 전달 구현 후 로컬 BE 8080·8081과 브라우저로 양방향 수신을 확인했습니다. heartbeat·인증 수정 후 최종 dev 검증은 별도로 진행합니다.

### 📌 FE 연동 및 후속 검증

- FE는 `resync_required` 수신 시 `/api/notifications`와 `/api/notifications/unread-count`를 재조회해야 합니다.
- FE의 `notification_id` 기준 중복 처리, 조회·실시간 이벤트 경합 처리, 인증 갱신·재구독은 별도 FE 작업입니다.
- dev에서 로드밸런서·프록시의 SSE buffering·idle timeout, 서버 교체 및 Redis 연결 장애·복구를 최종 검증합니다.
- Redis Pub/Sub은 이벤트를 보관·재생하지 않습니다. 이 PR은 DB 재조회 안내를 제공하며 Outbox·발행 재시도를 추가하지 않습니다.
- FE 연동·dev 통합 검증 후 이슈 전체 완료 기준을 확인합니다.

### 🔗 참고 자료

- 저장소 문서: `docs/notification-realtime-pubsub.md`
- [Spring Security 인증 보존](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)
- [Redis 구독 확인 콜백](https://docs.spring.io/spring-data/redis/docs/current/api/org/springframework/data/redis/connection/SubscriptionListener.html)
