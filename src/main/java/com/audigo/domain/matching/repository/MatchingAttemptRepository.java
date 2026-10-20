package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchingAttemptRepository extends JpaRepository<MatchingAttempt, Long> {

    boolean existsByMatchingRequest_IdAndStatus(Long matchingRequestId, MatchingAttemptStatus status);

    @EntityGraph(attributePaths = "candidates")
    Optional<MatchingAttempt> findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
            Long matchingRequestId,
            MatchingAttemptStatus status
    );

    Optional<MatchingAttempt> findFirstByMatchingRequest_IdOrderByIdDesc(Long matchingRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<MatchingAttempt> findAllByMatchingRequest_IdAndStatus(
            Long matchingRequestId,
            MatchingAttemptStatus status
    );

    @Query("select attempt.matchingRequest.id from MatchingAttempt attempt where attempt.id = :attemptId")
    Optional<Long> findMatchingRequestIdById(@Param("attemptId") Long attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from MatchingAttempt attempt where attempt.id = :attemptId")
    Optional<MatchingAttempt> findByIdForUpdate(@Param("attemptId") Long attemptId);
}
