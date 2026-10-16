package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationPayload;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.domain.travel.repository.TravelGenerationPayloadRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TravelGenerationPayloadStore {

    private final TravelGenerationPayloadRepository payloadRepository;
    private final TravelGenerationJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public TravelGenerationPayloadStore(
            TravelGenerationPayloadRepository payloadRepository,
            TravelGenerationJobRepository jobRepository,
            ObjectMapper objectMapper
    ) {
        this.payloadRepository = payloadRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void saveRequest(Long jobId, AiTravelGenerationRequest request) {
        try {
            String serialized = objectMapper.writeValueAsString(request);
            TravelGenerationPayload payload = payloadRepository.findByJobId(jobId)
                    .map(existing -> {
                        existing.replaceRequest(serialized);
                        return existing;
                    })
                    .orElseGet(() -> TravelGenerationPayload.create(getJob(jobId), serialized));
            payloadRepository.save(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 요청 스냅샷을 직렬화할 수 없습니다.", exception);
        }
    }

    @Transactional(readOnly = true)
    public AiTravelGenerationRequest getRequest(Long jobId) {
        String payload = getPayload(jobId).getRequestPayload();
        try {
            return objectMapper.readValue(payload, AiTravelGenerationRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 AI 요청 스냅샷을 복원할 수 없습니다.", exception);
        }
    }

    @Transactional
    public void saveResult(Long jobId, String resultPayload) {
        TravelGenerationPayload payload = getPayload(jobId);
        payload.saveResult(resultPayload);
        payloadRepository.save(payload);
    }

    @Transactional(readOnly = true)
    public String getResult(Long jobId) {
        return payloadRepository.findByJobId(jobId)
                .map(TravelGenerationPayload::getResultPayload)
                .orElse(null);
    }

    private TravelGenerationPayload getPayload(Long jobId) {
        return payloadRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private TravelGenerationJob getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }
}
