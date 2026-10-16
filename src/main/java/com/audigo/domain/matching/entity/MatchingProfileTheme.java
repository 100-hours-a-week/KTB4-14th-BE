package com.audigo.domain.matching.entity;

import com.audigo.domain.travel.entity.TravelThemeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;

@Entity
@Table(
        name = "matching_profile_themes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_matching_profile_theme",
                columnNames = {"matching_profile_id", "theme"}
        )
)
public class MatchingProfileTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_profile_id", nullable = false)
    private MatchingProfile matchingProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    private TravelThemeType theme;

    protected MatchingProfileTheme() {
    }

    private MatchingProfileTheme(MatchingProfile matchingProfile, TravelThemeType theme) {
        this.matchingProfile = Objects.requireNonNull(matchingProfile, "매칭 프로필은 필수입니다.");
        this.theme = Objects.requireNonNull(theme, "선호 테마는 필수입니다.");
    }

    static MatchingProfileTheme create(MatchingProfile matchingProfile, TravelThemeType theme) {
        return new MatchingProfileTheme(matchingProfile, theme);
    }

    public TravelThemeType getTheme() {
        return theme;
    }
}
