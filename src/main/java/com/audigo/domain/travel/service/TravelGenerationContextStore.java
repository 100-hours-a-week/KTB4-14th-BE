package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 요청 DTO의 장소명·주소·좌표를 DB에 저장하지 않고 AI 작업 동안만 보관
@Component
public class TravelGenerationContextStore {

    private final Map<Long, AiTravelGenerationRequest> contexts = new ConcurrentHashMap<>();

    public void put(Long jobId, AiTravelGenerationRequest request) {
        contexts.put(jobId, request);
    }

    public AiTravelGenerationRequest get(Long jobId) {
        return contexts.get(jobId);
    }

    public void remove(Long jobId) {
        contexts.remove(jobId);
    }
}
