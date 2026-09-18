package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RegionRepository extends JpaRepository<Region, Long> {
    List<Region> findAllByOrderByFullNameAsc();
}
