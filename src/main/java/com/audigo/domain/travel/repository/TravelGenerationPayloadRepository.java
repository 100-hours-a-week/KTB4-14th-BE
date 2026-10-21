package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationPayload;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelGenerationPayloadRepository extends JpaRepository<TravelGenerationPayload, Long> {

    Optional<TravelGenerationPayload> findByJobId(Long jobId);
}
