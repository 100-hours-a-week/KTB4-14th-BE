package com.audigo.domain.chat.service;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageSearchOutbox;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.Optional;
import com.audigo.domain.chat.repository.ChatMessageSearchOutboxRepository.ClaimedEvent;

@Service
public class ChatMessageSearchOutboxStore {
    private final ChatMessageSearchOutboxProperties properties;
    private final ChatMessageSearchOutboxRepository repository;
    private final ObjectMapper mapper;

    public ChatMessageSearchOutboxStore(ChatMessageSearchOutboxProperties properties,
                                       ChatMessageSearchOutboxRepository repository, ObjectMapper mapper) {
        this.properties = properties;
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Must join the caller's message transaction; never persist this in an afterCommit callback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(ChatMessage message) {
        if (!properties.recordEnabled()) return;
        if (message.getId() == null || message.getStatus() != ChatMessageStatus.SENT) {
            throw new IllegalArgumentException("Only persisted SENT messages may be enqueued");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("event_type", ChatMessageSearchOutbox.EVENT_TYPE);
        payload.put("text_contract", ChatMessageSearchOutbox.TEXT_CONTRACT);
        payload.put("message_id", message.getId());
        payload.put("room_id", message.getChatRoom().getId());
        payload.put("status", message.getStatus().name());
        // Match MySQL DATETIME(6); keep UTC and full long IDs in the stored snapshot.
        payload.put("sent_at", message.getSentAt().truncatedTo(ChronoUnit.MICROS).toInstant(ZoneOffset.UTC).toString());
        payload.put("content", message.getContent());
        try {
            repository.insert(ChatMessageSearchOutbox.create(message.getId(), UUID.randomUUID().toString(),
                    properties.targetGeneration(), properties.targetIndex(), mapper.writeValueAsString(payload),
                    LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)));
        } catch (JsonProcessingException exception) {
            // Do not include content or credentials in the failure message.
            throw new IllegalStateException("Cannot serialize chat search event", exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ClaimedEvent> claim() {
        if (!properties.workerEnabled()) return Optional.empty();
        return repository.claim(properties.targetGeneration(), properties.targetIndex(), properties.leaseSeconds());
    }

    @Transactional
    public boolean markIndexed(ClaimedEvent event) { return repository.markIndexed(event); }

    @Transactional
    public boolean markFailure(ClaimedEvent event, boolean retryable, String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,100}")) throw new IllegalArgumentException("Safe error code required");
        return repository.markFailure(event, retryable, retryDelaySeconds(event.attemptCount()), code);
    }

    public int retryDelaySeconds(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        return (int)Math.min((long)properties.retryBaseSeconds() * multiplier, properties.retryMaxSeconds());
    }
}
