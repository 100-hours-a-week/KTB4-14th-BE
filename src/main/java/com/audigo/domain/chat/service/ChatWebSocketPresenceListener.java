package com.audigo.domain.chat.service;

import java.security.Principal;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class ChatWebSocketPresenceListener {

    private final ChatPresenceConnectionManager chatPresenceConnectionManager;

    public ChatWebSocketPresenceListener(ChatPresenceConnectionManager chatPresenceConnectionManager) {
        this.chatPresenceConnectionManager = chatPresenceConnectionManager;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        chatPresenceConnectionManager.connected(
                userId(event.getUser()),
                SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders())
        );
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        chatPresenceConnectionManager.disconnected(event.getSessionId());
    }

    private Long userId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return Long.parseLong(principal.getName());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
