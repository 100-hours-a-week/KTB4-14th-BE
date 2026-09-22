# 대중교통 실시간 갱신 구현 기록

## 설계문서·현재 코드 차이

- `docs/travel-domain-tech-spec.md`의 기본 흐름은 AI 작업 상태 폴링을 전제로 하지만, 이번 최신 요청은 `AiSseGenerationClient`를 통한 SSE 이벤트 순서를 명시한다. 이 작업에서는 최신 요청과 현재 브랜치 구현을 기준으로 SSE 흐름을 유지했다.
- `docs/travel-domain-code-guide.md`는 AI 결과 일정·경로 저장을 범위 밖으로 설명하지만, 현재 브랜치에는 `TravelItineraryPersistenceService`와 일정 조회 API가 이미 구현되어 있다. 이번 기능은 해당 저장·조회 흐름을 재사용했다.
- 일부 설계 문서와 현재 코드 가이드 사이에는 `places.place_name` 영속화 여부가 다르게 적혀 있다. 이번 작업에서는 현재 코드의 `provider`·`provider_place_id`만 저장하는 정책을 변경하지 않았다.
- 일정 완료 API와 `is_completed` 필드는 초기 ERD 스냅샷에 없지만, 현재 API 계약과 완료 후 갱신 흐름을 위해 현재 엔티티의 진행 필드를 그대로 사용했다.
- 현재 ERD 엔티티에는 노선명, 차량 정보, 실시간 도착 정보, 마지막 갱신 시각 컬럼이 없다. API 응답 계약을 유지하기 위해 이 값은 `TravelItineraryMetadataStore`에 임시 보관한다. 따라서 서버 재시작 뒤에는 DB에 저장된 AI 기본 경로만 남고 실시간 부가 정보는 다시 갱신해야 한다.
- 요청문서의 축약 AI Mock은 `route_segments`만 보내지만, 기존 저장 로직은 `result.days[].stops[]`도 필요로 한다. 단일 일차이고 `required_places`가 있는 경우에 한해 기존 필수 장소를 일정으로 연결하는 fallback을 추가했다. 필수 장소가 없거나 여러 날짜의 축약 결과는 장소·일정 관계를 추정할 수 없으므로 실패 처리한다.
- 음악 추천 단계는 기존 enum·상태 모델과의 호환성을 위해 남아 있지만, 필수 완료 단계·저장·외부 호출에는 포함하지 않는다.

## 외부 설정과 장애 처리

- 대중교통 API 주소는 `AUDIGO_TRANSIT_BASE_URL` 환경 변수 또는 `audigo.transit.base-url` 프로퍼티로 읽는다.
- 완료 시각을 `at` 쿼리 파라미터로 전달하며, 외부 API가 실패하거나 응답을 해석하지 못하면 AI 기본값과 `realtime=false` 상태를 유지하고 여행 생성·일정 완료 자체는 성공시킨다.
- 별도 메시지 큐는 사용하지 않고 기존 여행 생성 `@Async` 실행 구조를 유지한다.

Spring Boot 4에서 `RestClient` 응답을 Jackson 트리 타입으로 직접 변환할 때 발생한 호환성 문제와 확인 절차는 [Spring Boot 4 RestClient·Jackson 응답 변환 트러블슈팅](./spring-boot4-restclient-jackson-troubleshooting.md)을 참고한다.
