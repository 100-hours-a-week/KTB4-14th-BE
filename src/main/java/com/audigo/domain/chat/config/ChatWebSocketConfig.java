package com.audigo.domain.chat.config;

import com.audigo.global.config.CorsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties corsProperties;
    private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;
    private final ChatWebSocketHandshakeHandler handshakeHandler;
    private final ChatStompAuthorizationInterceptor authorizationInterceptor;
    private final TaskScheduler chatStompHeartbeatScheduler;

    public ChatWebSocketConfig(
            CorsProperties corsProperties,
            ChatWebSocketHandshakeInterceptor handshakeInterceptor,
            ChatWebSocketHandshakeHandler handshakeHandler,
            ChatStompAuthorizationInterceptor authorizationInterceptor,
            @Qualifier("chatStompHeartbeatScheduler") TaskScheduler chatStompHeartbeatScheduler
    ) {
        this.corsProperties = corsProperties;
        this.handshakeInterceptor = handshakeInterceptor;
        this.handshakeHandler = handshakeHandler;
        this.authorizationInterceptor = authorizationInterceptor;
        this.chatStompHeartbeatScheduler = chatStompHeartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .addInterceptors(handshakeInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOrigins(corsProperties.resolvedAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {10_000L, 10_000L})
                .setTaskScheduler(chatStompHeartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorizationInterceptor);
    }
}
