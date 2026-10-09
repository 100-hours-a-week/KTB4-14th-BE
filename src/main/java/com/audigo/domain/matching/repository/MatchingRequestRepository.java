package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingRequestRepository extends JpaRepository<MatchingRequest, Long> {

    boolean existsByRequester_Id(Long requesterId);

    Optional<MatchingRequest> findByRequester_Id(Long requesterId);
}
