package com.audigo.domain.travel.service;

import com.audigo.domain.travel.config.AiServerProperties;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TravelGenerationWatchdog {

    private final TravelGenerationJobService jobService;
    private final AiServerProperties properties;

    public TravelGenerationWatchdog(
            TravelGenerationJobService jobService,
            AiServerProperties properties
    ) {
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${audigo.ai.watchdog-delay-millis:10000}")
    public void failStaleJobs() {
        jobService.failStaleGeneratingJobs(Duration.ofSeconds(properties.jobTimeoutSeconds()));
    }
}
