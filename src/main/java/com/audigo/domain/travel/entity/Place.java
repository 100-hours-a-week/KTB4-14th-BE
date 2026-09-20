package com.audigo.domain.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "places",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_places_provider_place_id",
                columnNames = {"provider", "provider_place_id"}
        )
)
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PlaceProvider provider;

    @Column(name = "provider_place_id", nullable = false, length = 100)
    private String providerPlaceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Place() {
    }

    private Place(
            PlaceProvider provider,
            String providerPlaceId
    ) {
        this.provider = Objects.requireNonNull(provider, "장소 제공자는 필수입니다.");
        if (providerPlaceId == null || providerPlaceId.isBlank()) {
            throw new IllegalArgumentException("카카오 장소 ID는 필수입니다.");
        }
        String normalizedProviderPlaceId = providerPlaceId.trim();
        if (normalizedProviderPlaceId.length() > 100) {
            throw new IllegalArgumentException("카카오 장소 ID는 100자 이하여야 합니다.");
        }
        this.providerPlaceId = normalizedProviderPlaceId;
    }

    public static Place create(
            PlaceProvider provider,
            String providerPlaceId
    ) {
        return new Place(provider, providerPlaceId);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public PlaceProvider getProvider() {
        return provider;
    }

    public String getProviderPlaceId() {
        return providerPlaceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

}
