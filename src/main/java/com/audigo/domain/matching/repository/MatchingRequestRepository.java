package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchingRequestRepository extends JpaRepository<MatchingRequest, Long> {

    boolean existsByRequester_Id(Long requesterId);

    Optional<MatchingRequest> findByRequester_Id(Long requesterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from MatchingRequest request where request.requester.id = :requesterId")
    Optional<MatchingRequest> findByRequesterIdForUpdate(@Param("requesterId") Long requesterId);
}
