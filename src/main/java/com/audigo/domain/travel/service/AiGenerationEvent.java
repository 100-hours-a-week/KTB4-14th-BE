package com.audigo.domain.travel.service;

// SSE 한 건을 파싱
public record AiGenerationEvent(String eventType, String data) {
    public String normalizedType() {
        return eventType == null ? "" : eventType.trim().toUpperCase();
    }
}
