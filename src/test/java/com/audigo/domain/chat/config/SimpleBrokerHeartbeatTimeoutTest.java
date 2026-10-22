package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SimpleBrokerHeartbeatTimeoutTest {

    @Test
    void broker_disconnects_a_session_when_client_heartbeats_stop() throws Exception {
        SubscribableChannel inboundChannel = new ExecutorSubscribableChannel();
        MessageChannel outboundChannel = new ExecutorSubscribableChannel();
        SubscribableChannel brokerChannel = new ExecutorSubscribableChannel();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-stomp-heartbeat-");
        scheduler.initialize();

        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(
                inboundChannel,
                outboundChannel,
                brokerChannel,
                List.of("/queue")
        );
        broker.setHeartbeatValue(new long[] {20L, 20L});
        broker.setTaskScheduler(scheduler);

        AtomicBoolean disconnectAcknowledged = new AtomicBoolean();
        CopyOnWriteArrayList<SimpMessageType> outboundTypes = new CopyOnWriteArrayList<>();
        ((SubscribableChannel) outboundChannel).subscribe(message -> {
            SimpMessageType messageType = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
            outboundTypes.add(messageType);
            if (messageType == SimpMessageType.DISCONNECT_ACK) {
                disconnectAcknowledged.set(true);
            }
        });

        try {
            broker.start();
            broker.handleMessage(connectMessage("session-1"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!disconnectAcknowledged.get() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertThat(outboundTypes).contains(SimpMessageType.CONNECT_ACK, SimpMessageType.DISCONNECT_ACK);
        } finally {
            broker.stop();
            scheduler.shutdown();
        }
    }

    private Message<byte[]> connectMessage(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(() -> "42");
        accessor.setHeader(SimpMessageHeaderAccessor.HEART_BEAT_HEADER, new long[] {20L, 20L});
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
