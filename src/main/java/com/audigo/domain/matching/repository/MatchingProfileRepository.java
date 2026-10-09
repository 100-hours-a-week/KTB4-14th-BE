package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingProfileRepository extends JpaRepository<MatchingProfile, Long> {

    Optional<MatchingProfile> findByUser_Id(Long userId);
}
