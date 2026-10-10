package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.MatchingProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchingProfileRepository extends JpaRepository<MatchingProfile, Long> {

    Optional<MatchingProfile> findByUser_Id(Long userId);

    @Query("""
            select distinct profile
            from MatchingProfile profile
            left join fetch profile.themes
            where profile.active = true
              and profile.user.id <> :userId
            """)
    List<MatchingProfile> findActiveProfilesExceptUser(@Param("userId") Long userId);
}
