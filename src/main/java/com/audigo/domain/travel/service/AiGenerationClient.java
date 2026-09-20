package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.AiTravelGenerationRequest;
import java.util.function.Consumer;

public interface AiGenerationClient {

    void stream(AiTravelGenerationRequest request, Consumer<AiGenerationEvent> eventConsumer);
}
