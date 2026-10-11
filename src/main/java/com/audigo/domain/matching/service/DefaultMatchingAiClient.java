package com.audigo.domain.matching.service;

import com.audigo.domain.matching.config.MatchingAiProperties;
import com.audigo.domain.matching.dto.MatchingAiCandidateResponse;
import com.audigo.domain.matching.dto.MatchingAiCandidatesResponse;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.travel.config.AiServerProperties;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DefaultMatchingAiClient implements MatchingAiClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultMatchingAiClient.class);

    private final RestClient restClient;
    private final AiServerProperties aiProperties;
    private final MatchingAiProperties matchingAiProperties;

    @Autowired
    public DefaultMatchingAiClient(
            RestClient.Builder restClientBuilder,
            AiServerProperties aiProperties,
            MatchingAiProperties matchingAiProperties
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(aiProperties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(aiProperties.readTimeoutSeconds()));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.aiProperties = aiProperties;
        this.matchingAiProperties = matchingAiProperties;
    }

    DefaultMatchingAiClient(
            RestClient restClient,
            AiServerProperties aiProperties,
            MatchingAiProperties matchingAiProperties
    ) {
        this.restClient = restClient;
        this.aiProperties = aiProperties;
        this.matchingAiProperties = matchingAiProperties;
    }

    @Override
    public MatchingAiCandidatesResponse getCandidates(MatchingCandidateAiRequest request) {
        if (matchingAiProperties.mockEnabled()) {
            return mockCandidates(request);
        }
        if (aiProperties.baseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        String url = joinUrl(aiProperties.baseUrl(), matchingAiProperties.candidatesPath());
        log.info(
                "matching_ai_candidates_request_start url={} matching_request_id={} user_id={} auth_configured={}",
                url,
                request.matchingRequestId(),
                request.userId(),
                !aiProperties.apiToken().isBlank()
        );
        try {
            MatchingAiCandidatesResponse response = restClient.post()
                    .uri(url)
                    .headers(headers -> {
                        if (!aiProperties.apiToken().isBlank()) {
                            headers.setBearerAuth(aiProperties.apiToken());
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MatchingAiCandidatesResponse.class);
            MatchingAiCandidatesResponse normalized = response == null
                    ? MatchingAiCandidatesResponse.empty()
                    : response;
            log.info(
                    "matching_ai_candidates_request_complete matching_request_id={} count={}",
                    request.matchingRequestId(),
                    normalized.count()
            );
            return normalized;
        } catch (RestClientResponseException exception) {
            log.warn(
                    "matching_ai_candidates_request_rejected matching_request_id={} status={}",
                    request.matchingRequestId(),
                    exception.getStatusCode(),
                    exception
            );
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (RestClientException exception) {
            log.warn(
                    "matching_ai_candidates_request_failed matching_request_id={} reason=rest_client",
                    request.matchingRequestId(),
                    exception
            );
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private MatchingAiCandidatesResponse mockCandidates(MatchingCandidateAiRequest request) {
        int limit = matchingAiProperties.candidateLimit();
        List<MatchingCandidateAiRequest.CandidateProfilePayload> source = request.candidates();
        List<MatchingAiCandidateResponse> candidates = IntStream.range(0, Math.min(limit, source.size()))
                .mapToObj(index -> new MatchingAiCandidateResponse(
                        source.get(index).userId(),
                        Math.max(60, 92 - index * 7)
                ))
                .toList();
        log.info(
                "matching_ai_candidates_mocked matching_request_id={} count={}",
                request.matchingRequestId(),
                candidates.size()
        );
        return MatchingAiCandidatesResponse.of(candidates);
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }
}
