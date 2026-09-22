package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByProviderAndProviderPlaceId(PlaceProvider provider, String providerPlaceId);
}
