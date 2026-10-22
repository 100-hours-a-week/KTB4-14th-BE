package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.audigo.global.config.CorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.TaskScheduler;

class ChatWebSocketHeartbeatConfigTest {

    @Test
    void simple_broker_uses_ten_second_heartbeats_in_both_directions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            SimpleBrokerMessageHandler broker = context.getBean(SimpleBrokerMessageHandler.class);

            assertThat(broker.getHeartbeatValue()).containsExactly(10_000L, 10_000L);
            assertThat(broker.getTaskScheduler())
                    .isSameAs(context.getBean("chatStompHeartbeatScheduler", TaskScheduler.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ChatWebSocketConfig.class, ChatWebSocketSchedulerConfig.class})
    static class TestConfig {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("http://localhost:5173"));
        }

        @Bean
        ChatWebSocketHandshakeInterceptor handshakeInterceptor() {
            return mock(ChatWebSocketHandshakeInterceptor.class);
        }

        @Bean
        ChatWebSocketHandshakeHandler handshakeHandler() {
            return mock(ChatWebSocketHandshakeHandler.class);
        }

        @Bean
        ChatStompAuthorizationInterceptor authorizationInterceptor() {
            return mock(ChatStompAuthorizationInterceptor.class);
        }
    }
}
