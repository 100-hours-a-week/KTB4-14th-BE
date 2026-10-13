package com.audigo.domain.chat.service;

import java.security.Principal;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class ChatWebSocketPresenceListener {

    private final ChatPresenceRegistry chatPresenceRegistry;

    public ChatWebSocketPresenceListener(ChatPresenceRegistry chatPresenceRegistry) {
        this.chatPresenceRegistry = chatPresenceRegistry;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        register(event.getUser(), SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders()), true);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        register(event.getUser(), event.getSessionId(), false);
    }

    private void register(Principal principal, String sessionId, boolean connected) {
        if (principal == null || sessionId == null) {
            return;
        }
        try {
            Long userId = Long.parseLong(principal.getName());
            if (connected) {
                chatPresenceRegistry.connected(userId, sessionId);
            } else {
                chatPresenceRegistry.disconnected(userId, sessionId);
            }
        } catch (NumberFormatException ignored) {
            // Only authenticated application users are registered in chat presence.
        }
    }
}
