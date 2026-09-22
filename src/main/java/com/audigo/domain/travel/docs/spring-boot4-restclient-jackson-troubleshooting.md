# Spring Boot 4 RestClient·Jackson 응답 변환 트러블슈팅

## 목적

대중교통 실시간 정보 조회 중 Spring Boot 4의 기본 JSON 응답 변환기와 기존 Jackson 트리 타입의 조합에서 발생한 오류를 재현하고, 동일한 문제가 다시 발생했을 때 확인할 순서와 해결 방법을 기록한다.

## 증상

`RestClient` 응답을 `com.fasterxml.jackson.databind.JsonNode`로 바로 변환하려고 하면 다음과 유사한 예외가 발생한다.

```text
org.springframework.http.converter.HttpMessageConversionException:
Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]

Caused by: tools.jackson.databind.exc.InvalidDefinitionException:
Cannot construct instance of `com.fasterxml.jackson.databind.JsonNode`
```

실패 지점의 예시는 다음과 같다.

```java
JsonNode response = restClient.get()
        .uri(requestUri)
        .retrieve()
        .body(JsonNode.class);
```

## 원인

현재 Audigo 여행 도메인은 AI SSE와 교통 응답을 해석할 때 Jackson 2 패키지의 타입을 사용한다.

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
```

반면 Spring Boot 4의 기본 HTTP 메시지 변환 경로는 Jackson 3 계열(`tools.jackson.*`)을 사용한다. 따라서 `body(JsonNode.class)`를 호출하면 다음 경계에서 패키지가 달라진다.

| 구간 | 현재 사용 타입 |
| --- | --- |
| 애플리케이션이 변환을 요청한 대상 | `com.fasterxml.jackson.databind.JsonNode` |
| Spring Boot 4 기본 변환기가 사용하는 구현 | `tools.jackson.databind.*` |

즉 JSON 자체가 잘못된 것이 아니라, Spring의 기본 변환기가 Jackson 2의 추상 트리 타입을 직접 생성하려는 과정에서 호환되지 않는 변환 경계가 만들어진다. 예외의 원인 체인에 `tools.jackson.databind...`와 `com.fasterxml.jackson.databind.JsonNode`가 함께 나타나는지 확인하면 이 문제를 빠르게 식별할 수 있다.

## 적용한 해결 방법

Spring의 메시지 변환기에게 트리 타입 생성을 맡기지 않고, 먼저 응답을 문자열로 받은 뒤 애플리케이션이 이미 사용 중인 Jackson 2 `ObjectMapper`로 파싱한다.

```java
String responseBody = restClient.get()
        .uri(requestUri)
        .retrieve()
        .body(String.class);

JsonNode response = OBJECT_MAPPER.readTree(responseBody);
```

현재 구현은 [PublicTransportRealtimeService.java](/Users/sohyeon/audigo/BE/src/main/java/com/audigo/domain/travel/service/PublicTransportRealtimeService.java)에 반영되어 있다.

이 방식의 장점은 다음과 같다.

- HTTP 계층에서는 문자열 응답만 변환하므로 Spring Boot 4의 Jackson 3 기본 변환기와 충돌하지 않는다.
- 기존 AI/SSE 파싱 코드와 같은 `com.fasterxml.jackson.databind.ObjectMapper`를 계속 사용할 수 있다.
- 의존성 전체를 Jackson 3으로 마이그레이션하지 않고 여행 도메인 내부의 변경 범위를 최소화한다.
- 빈 응답, 잘못된 JSON, 외부 API 오류를 한 곳에서 처리하고 기존 교통 메타데이터를 보존할 수 있다.

## 장애 처리 규칙

문자열 응답을 파싱하는 방식으로 변경하더라도 외부 API가 항상 정상이라는 보장은 없으므로 다음 규칙을 유지한다.

1. 응답이 비어 있거나 JSON 형식이 아니면 실시간 갱신을 적용하지 않는다.
2. 외부 API 호출 또는 파싱 중 예외가 발생하면 기존 `line`, `vehicle`, `next_arrival_minutes` 메타데이터를 보존한다.
3. 실패한 경우 `realtime`은 `false`로 유지한다.
4. 실시간 갱신 실패만으로 여행 생성 또는 일정 완료 요청 전체를 실패시키지 않는다.
5. 실패 원인은 경고 로그에 남기되, 외부 응답 원문이나 인증 정보는 로그에 기록하지 않는다.

## 확인 절차

같은 오류가 다시 발생하면 아래 순서로 확인한다.

1. 대상 타입의 import가 `com.fasterxml.jackson.databind.JsonNode`인지 확인한다.
2. `RestClient`에서 `.body(JsonNode.class)` 또는 동일한 트리 타입 직접 변환을 사용하고 있는지 검색한다.
3. 예외 원인에 `tools.jackson.databind`가 포함되어 있는지 확인한다.
4. `.body(String.class)`로 원문을 받고 기존 `com.fasterxml.jackson.databind.ObjectMapper#readTree`로 파싱한다.
5. 빈 응답·잘못된 JSON·외부 호출 실패 시 기존 메타데이터가 보존되는 테스트를 추가하거나 확인한다.
6. 다음 검증을 실행한다.

```bash
bash gradlew test --no-daemon
```

실시간 교통 서비스 테스트에서는 정상 응답, snake/camel case 필드, 도착 시간 필드, 잘못된 응답 및 외부 호출 실패 시의 fallback 동작을 검증한다.

## 피해야 할 대응

- 예외 메시지만 보고 Jackson 의존성을 무작정 추가하거나 버전을 섞지 않는다.
- `tools.jackson.databind.JsonNode`로 일부 코드만 바꾸지 않는다. 현재 여행 도메인의 SSE와 AI 파서까지 함께 마이그레이션하지 않으면 또 다른 타입 경계 문제가 생길 수 있다.
- 외부 API 실패를 여행 생성 전체 실패로 전파하지 않는다.

## 향후 Jackson 3 마이그레이션 시

Jackson 3을 표준으로 전환하려면 `ObjectMapper`, `JsonNode`, SSE 파서, 테스트 픽스처 처리 코드의 import와 설정을 일관되게 바꾼 뒤 전체 여행 생성·일정 완료 흐름을 다시 검증해야 한다. 일부 호출부만 변경하는 대신 애플리케이션의 JSON 처리 경계를 한 번에 정리하는 방식으로 진행한다.

관련 구현 차이와 범위는 [실시간 교통 구현 기록](./realtime-transport-implementation.md)을 함께 참고한다.
