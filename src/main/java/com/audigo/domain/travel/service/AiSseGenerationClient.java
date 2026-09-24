package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.AiServerProperties;
import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// AI 서버의 SSE 스트림을 백엔드 작업 스레드에서 읽기
@Component
public class AiSseGenerationClient implements AiGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(AiSseGenerationClient.class);

    private final RestClient restClient;
    private final AiServerProperties properties;

    @Autowired
    public AiSseGenerationClient(RestClient.Builder restClientBuilder, AiServerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    AiSseGenerationClient(RestClient restClient, AiServerProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public void stream(AiTravelGenerationRequest request, Consumer<AiGenerationEvent> eventConsumer) {
        if (properties.baseUrl().isBlank()) {
            throw new IllegalStateException("AI 서버 주소가 설정되지 않았습니다.");
        }

        String url = joinUrl(properties.baseUrl(), properties.ssePath());
        log.info(
                "ai_sse_request_start url={} travel_plan_id={} required_places={} auth_configured={}",
                url,
                request.travelPlanId(),
                request.requiredPlaces().size(),
                !properties.apiToken().isBlank()
        );
        try {
            restClient.post()
                    .uri(url)
                    .headers(headers -> {
                        if (!properties.apiToken().isBlank()) {
                            headers.setBearerAuth(properties.apiToken());
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        log.info(
                                "ai_sse_response_status travel_plan_id={} status={}",
                                request.travelPlanId(),
                                response.getStatusCode()
                        );
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("AI 서버가 여행 생성 요청을 거부했습니다.");
                        }
                        readEvents(response.getBody(), eventConsumer);
                        return null;
                    });
            log.info("ai_sse_request_complete travel_plan_id={}", request.travelPlanId());
        } catch (RestClientException exception) {
            log.warn("ai_sse_request_failed travel_plan_id={} reason=rest_client", request.travelPlanId(), exception);
            throw new IllegalStateException("AI 서버와 연결하지 못했습니다.", exception);
        } catch (RuntimeException exception) {
            log.warn("ai_sse_request_failed travel_plan_id={} reason=runtime", request.travelPlanId(), exception);
            throw exception;
        }
    }

    private void readEvents(java.io.InputStream inputStream, Consumer<AiGenerationEvent> eventConsumer)
            throws IOException {
        if (inputStream == null) {
            throw new IOException("AI 서버가 SSE 응답 본문을 보내지 않았습니다.");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String eventType = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emit(eventType, data, eventConsumer);
                    eventType = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            emit(eventType, data, eventConsumer);
        }
    }

    private void emit(String eventType, StringBuilder data, Consumer<AiGenerationEvent> eventConsumer) {
        if ((eventType == null || eventType.isBlank()) && data.isEmpty()) {
            return;
        }
        log.info(
                "ai_sse_event_received event_type={} data_bytes={}",
                eventType == null || eventType.isBlank() ? "-" : eventType,
                data.length()
        );
        eventConsumer.accept(new AiGenerationEvent(eventType, data.toString()));
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }
}
