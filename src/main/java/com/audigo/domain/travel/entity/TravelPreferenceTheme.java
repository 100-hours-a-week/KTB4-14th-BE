package com.audigo.domain.travel.entity;

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
        name = "travel_preference_themes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_travel_preference_theme",
                columnNames = {"travel_preference_id", "theme"}
        )
)
public class TravelPreferenceTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_preference_id", nullable = false)
    private TravelPreference travelPreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 30)
    private TravelThemeType theme;

    protected TravelPreferenceTheme() {
    }

    private TravelPreferenceTheme(TravelPreference travelPreference, TravelThemeType theme) {
        this.travelPreference = Objects.requireNonNull(travelPreference, "여행 취향은 필수입니다.");
        this.theme = Objects.requireNonNull(theme, "테마는 필수입니다.");
    }

    static TravelPreferenceTheme create(TravelPreference travelPreference, TravelThemeType theme) {
        return new TravelPreferenceTheme(travelPreference, theme);
    }

    public TravelThemeType getTheme() {
        return theme;
    }
}
