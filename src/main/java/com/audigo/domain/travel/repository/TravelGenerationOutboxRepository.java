package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationOutbox;
import com.audigo.domain.travel.entity.TravelGenerationOutboxStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelGenerationOutboxRepository extends JpaRepository<TravelGenerationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event
            from TravelGenerationOutbox event
            where (event.status = :pendingStatus and event.nextAttemptAt <= :now)
               or (event.status = :processingStatus and event.lockedAt <= :leaseExpiredAt)
            order by event.id
            """)
    List<TravelGenerationOutbox> findReadyForPublish(
            @Param("pendingStatus") TravelGenerationOutboxStatus pendingStatus,
            @Param("processingStatus") TravelGenerationOutboxStatus processingStatus,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiredAt") LocalDateTime leaseExpiredAt,
            Pageable pageable
    );
}
