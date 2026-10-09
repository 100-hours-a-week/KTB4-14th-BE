package com.audigo.domain.matching.entity;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "matching_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_matching_profiles_user",
                columnNames = "user_id"
        )
)
public class MatchingProfile {

    public static final int MAX_THEME_COUNT = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "pace", nullable = false, length = 20)
    private TravelPaceType pace;

    @OneToMany(mappedBy = "matchingProfile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MatchingProfileTheme> themes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MatchingProfile() {
    }

    private MatchingProfile(
            User user,
            boolean active,
            TravelPaceType pace,
            List<TravelThemeType> themes
    ) {
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
        update(active, pace, themes);
    }

    public static MatchingProfile create(
            User user,
            boolean active,
            TravelPaceType pace,
            List<TravelThemeType> themes
    ) {
        return new MatchingProfile(user, active, pace, themes);
    }

    public void update(boolean active, TravelPaceType pace, List<TravelThemeType> themes) {
        this.active = active;
        this.pace = Objects.requireNonNull(pace, "여행 속도는 필수입니다.");
        replaceThemes(themes);
    }

    private void replaceThemes(List<TravelThemeType> values) {
        List<TravelThemeType> normalized = List.copyOf(Objects.requireNonNull(values, "선호 테마 목록은 필수입니다."));
        if (normalized.size() > MAX_THEME_COUNT || new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("선호 테마는 4개 이하의 중복 없는 값이어야 합니다.");
        }
        themes.clear();
        normalized.forEach(theme -> themes.add(MatchingProfileTheme.create(this, theme)));
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public TravelPaceType getPace() {
        return pace;
    }

    public List<MatchingProfileTheme> getThemes() {
        return Collections.unmodifiableList(themes);
    }

    public boolean isComplete() {
        return pace != null && !themes.isEmpty();
    }

    public boolean canMatch() {
        return active && isComplete();
    }
}
