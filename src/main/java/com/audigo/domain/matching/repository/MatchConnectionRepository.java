package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchConnectionRepository extends JpaRepository<MatchConnection, Long> {

    Optional<MatchConnection> findByMatchingRequest_IdAndTargetUser_IdAndStatus(
            Long matchingRequestId,
            Long targetUserId,
            MatchConnectionStatus status
    );
}
