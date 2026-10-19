package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchConnectionRepository extends JpaRepository<MatchConnection, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from MatchConnection connection where connection.id = :connectionId")
    Optional<MatchConnection> findByIdForUpdate(@Param("connectionId") Long connectionId);

    Optional<MatchConnection> findByMatchingRequest_IdAndTargetUser_IdAndStatus(
            Long matchingRequestId,
            Long targetUserId,
            MatchConnectionStatus status
    );

    @Query("""
            select case
                when request.requester.id = :userId then target.id
                else request.requester.id
            end
            from MatchConnection connection
            join connection.matchingRequest request
            join connection.targetUser target
            where connection.status = :status
              and (request.requester.id = :userId or target.id = :userId)
            """)
    List<Long> findCounterpartUserIds(
            @Param("userId") Long userId,
            @Param("status") MatchConnectionStatus status
    );
}
