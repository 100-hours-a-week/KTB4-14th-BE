package com.audigo.domain.chat.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatLocalRealtimeDispatcher {

    private static final String RECIPIENT_DESTINATION = "/queue/chat/messages";

    private final SimpMessagingTemplate messagingTemplate;

    public ChatLocalRealtimeDispatcher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void deliver(Long recipientUserId, Object payload) {
        messagingTemplate.convertAndSendToUser(
                recipientUserId.toString(),
                RECIPIENT_DESTINATION,
                payload
        );
    }
}
