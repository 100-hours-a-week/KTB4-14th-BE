package com.audigo.domain.chat.config;

import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class ChatStompAuthorizationInterceptor implements ChannelInterceptor {

    private static final String MESSAGE_SUBSCRIPTION = "/user/queue/chat/messages";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        Principal user = accessor.getUser();
        if (accessor.getCommand() == StompCommand.CONNECT) {
            requireAuthenticated(user);
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            requireAuthenticated(user);
            if (!MESSAGE_SUBSCRIPTION.equals(accessor.getDestination())) {
                throw new AccessDeniedException("Only the authenticated user's chat message queue is subscribable.");
            }
        } else if (accessor.getCommand() == StompCommand.SEND) {
            throw new AccessDeniedException("Client message publishing is not supported.");
        }
        return message;
    }

    private void requireAuthenticated(Principal user) {
        if (user == null) {
            throw new AccessDeniedException("WebSocket authentication is required.");
        }
    }
}
