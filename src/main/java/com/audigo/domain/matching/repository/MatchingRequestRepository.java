package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MatchingRequestRepository extends JpaRepository<MatchingRequest, Long> {

    boolean existsByRequester_IdAndStatus(Long requesterId, MatchingRequestStatus status);

    Optional<MatchingRequest> findByRequester_IdAndStatus(Long requesterId, MatchingRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MatchingRequest> findFirstByRequester_IdAndStatusInOrderByIdDesc(
            Long requesterId,
            Collection<MatchingRequestStatus> statuses
    );
}
