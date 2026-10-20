package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchingRequestRepository extends JpaRepository<MatchingRequest, Long> {

    boolean existsByRequester_IdAndStatus(Long requesterId, MatchingRequestStatus status);

    Optional<MatchingRequest> findByRequester_IdAndStatus(Long requesterId, MatchingRequestStatus status);

    Optional<MatchingRequest> findFirstByRequester_IdOrderByIdDesc(Long requesterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MatchingRequest> findFirstByRequester_IdAndStatusInOrderByIdDesc(
            Long requesterId,
            Collection<MatchingRequestStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from MatchingRequest request where request.id = :requestId")
    Optional<MatchingRequest> findByIdForUpdate(@Param("requestId") Long requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from MatchingRequest request
            where request.id = (
                select max(latest.id)
                from MatchingRequest latest
                where latest.requester.id = :requesterId
            )
            """)
    Optional<MatchingRequest> findLatestByRequesterIdForUpdate(@Param("requesterId") Long requesterId);
}
