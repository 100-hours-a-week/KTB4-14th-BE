package com.audigo.domain.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ChatWebSocketSchedulerConfig {

    @Bean("chatStompHeartbeatScheduler")
    ThreadPoolTaskScheduler chatStompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-stomp-heartbeat-");
        return scheduler;
    }
}
