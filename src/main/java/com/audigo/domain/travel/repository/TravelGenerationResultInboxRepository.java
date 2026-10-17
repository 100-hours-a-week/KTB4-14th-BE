package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationResultInbox;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelGenerationResultInboxRepository
        extends JpaRepository<TravelGenerationResultInbox, Long> {

    Optional<TravelGenerationResultInbox> findByEventId(String eventId);

    boolean existsBySqsMessageId(String sqsMessageId);
}
