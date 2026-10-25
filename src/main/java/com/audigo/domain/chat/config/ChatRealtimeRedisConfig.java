package com.audigo.domain.chat.config;

import com.audigo.domain.chat.realtime.RedisChatRealtimeListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ChatRealtimeRedisConfig {

    @Bean
    RedisMessageListenerContainer chatRealtimeRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisChatRealtimeListener listener,
            ChatRealtimeProperties properties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(properties.channel()));
        return container;
    }
}
