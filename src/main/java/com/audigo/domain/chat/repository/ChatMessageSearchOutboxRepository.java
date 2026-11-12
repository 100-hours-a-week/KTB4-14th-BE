package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatMessageSearchOutbox;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMessageSearchOutboxRepository {
    private final JdbcTemplate jdbc;

    public ChatMessageSearchOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(ChatMessageSearchOutbox event) {
        jdbc.update("""
                INSERT INTO chat_message_search_outbox
                    (event_id,event_type,schema_version,text_contract,message_id,target_generation,target_index,
                     payload,status,attempt_count,next_attempt_at,created_at,updated_at)
                VALUES (?,?,1,?,?,?,?,?,'PENDING',0,?,?,?)
                """, event.eventId(), ChatMessageSearchOutbox.EVENT_TYPE, ChatMessageSearchOutbox.TEXT_CONTRACT,
                event.messageId(), event.targetGeneration(), event.targetIndex(), event.payload(),
                event.createdAt(), event.createdAt(), event.createdAt());
    }

    /** Caller must hold a short transaction; this query is exercised against MySQL 8.4. */
    public Optional<ClaimedEvent> claim(String generation, String index, int leaseSeconds) {
        List<ClaimedEvent> candidates = jdbc.query("""
                SELECT id,event_id,message_id,payload,attempt_count
                FROM chat_message_search_outbox
                WHERE target_generation=? AND target_index=?
                  AND ((status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6))
                    OR (status='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6)))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, n) -> new ClaimedEvent(row.getLong("id"), row.getString("event_id"),
                row.getLong("message_id"), row.getString("payload"), generation, index,
                UUID.randomUUID().toString(), row.getInt("attempt_count") + 1), generation, index);
        if (candidates.isEmpty()) return Optional.empty();
        ClaimedEvent event = candidates.getFirst();
        jdbc.update("""
                UPDATE chat_message_search_outbox SET status='PROCESSING',lease_token=?,
                lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),
                attempt_count=attempt_count+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?
                """, event.leaseToken(), leaseSeconds, event.id());
        return Optional.of(event);
    }

    public boolean markIndexed(ClaimedEvent event) {
        return jdbc.update("""
                UPDATE chat_message_search_outbox SET status='INDEXED',indexed_at=UTC_TIMESTAMP(6),
                lease_token=NULL,lease_until=NULL,last_error_code=NULL,last_error_at=NULL,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
                """, event.id(), event.leaseToken()) == 1;
    }

    public boolean markFailure(ClaimedEvent event, boolean retryable, int delaySeconds, String code) {
        return jdbc.update("""
                UPDATE chat_message_search_outbox SET status=?,
                next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),
                lease_token=NULL,lease_until=NULL,last_error_code=?,last_error_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
                """, retryable ? "PENDING" : "FAILED", delaySeconds, code, event.id(), event.leaseToken()) == 1;
    }

    public record ClaimedEvent(long id, String eventId, long messageId, String payload,
                               String targetGeneration, String targetIndex, String leaseToken, int attemptCount) {}
}
