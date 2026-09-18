package com.audigo.domain.travel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "regions",
    uniqueConstraints = {@UniqueConstraint(name = "uk_regions_full_name", columnNames = {"full_name"})}
)

public class Region {
    // 필드
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // jpa 기본 생성자
    protected Region(){
    }

    private Region(String name, String fullName) {
        this.name = requireText(name, "지역명", 100);
        this.fullName = requireText(fullName, "전체 지역명", 150);
    }

    public static Region create(String name, String fullName) {
        return new Region(name, fullName);
    }

    // 날짜 생성 저장
    @PrePersist
    protected void onCreate(){
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;
    }
    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = LocalDateTime.now();
    }

    // GETTER
    public Long getId(){
        return id;
    }

    public String getName(){
        return name;
    }

    public String getFullName(){
        return fullName;
    }

    public LocalDateTime getCreatedAt(){
        return createdAt;
    }

    public LocalDateTime getUpdatedAt(){
        return updatedAt;
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        String normalized = Objects.requireNonNull(value, fieldName + "은(는) 필수입니다.").trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "은(는) 비어 있지 않고 " + maxLength + "자 이하여야 합니다.");
        }
        return normalized;
    }
}
