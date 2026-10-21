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
    private final ChatPresenceNotificationService chatPresenceNotificationService;

    public ChatWebSocketPresenceListener(
            ChatPresenceRegistry chatPresenceRegistry,
            ChatPresenceNotificationService chatPresenceNotificationService
    ) {
        this.chatPresenceRegistry = chatPresenceRegistry;
        this.chatPresenceNotificationService = chatPresenceNotificationService;
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
                if (chatPresenceRegistry.connected(userId, sessionId)) {
                    chatPresenceNotificationService.publishStatusChange(userId, true);
                }
            } else if (chatPresenceRegistry.disconnected(userId, sessionId)) {
                chatPresenceNotificationService.publishStatusChange(userId, false);
            }
        } catch (NumberFormatException ignored) {
        }
    }
}
