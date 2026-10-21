package com.audigo.domain.travel.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(prefix = "audigo.sqs", name = "enabled", havingValue = "true")
public class TravelResultQueueConsumerConfig {

    @Bean(name = "travelResultQueueScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler travelResultQueueScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("travel-result-poller-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "travelResultQueueExecutor")
    ThreadPoolTaskExecutor travelResultQueueExecutor(TravelResultQueueProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(properties.executorQueueCapacity());
        executor.setThreadNamePrefix("travel-result-handler-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
