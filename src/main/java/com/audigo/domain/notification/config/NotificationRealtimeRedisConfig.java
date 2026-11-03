package com.audigo.domain.notification.config;

import com.audigo.domain.notification.realtime.RedisNotificationRealtimeListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class NotificationRealtimeRedisConfig {

    @Bean
    RedisMessageListenerContainer notificationRealtimeRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisNotificationRealtimeListener listener,
            NotificationRealtimeProperties properties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(properties.channel()));
        return container;
    }
}
