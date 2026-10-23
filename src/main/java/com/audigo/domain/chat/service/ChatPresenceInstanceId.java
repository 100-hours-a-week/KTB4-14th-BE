package com.audigo.domain.chat.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ChatPresenceInstanceId {

    private final String value;

    public ChatPresenceInstanceId() {
        this(UUID.randomUUID().toString());
    }

    ChatPresenceInstanceId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Chat presence instance id must not be blank");
        }
        this.value = value;
    }

    public String value() {
        return value;
    }
}
