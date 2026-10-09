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
        name = "matching_requests_themes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_matching_request_theme",
                columnNames = {"matching_request_id", "theme"}
        )
)
public class MatchingRequestTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_request_id", nullable = false)
    private MatchingRequest matchingRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    private TravelThemeType theme;

    protected MatchingRequestTheme() {
    }

    private MatchingRequestTheme(MatchingRequest matchingRequest, TravelThemeType theme) {
        this.matchingRequest = Objects.requireNonNull(matchingRequest, "매칭 요청은 필수입니다.");
        this.theme = Objects.requireNonNull(theme, "선호 테마는 필수입니다.");
    }

    static MatchingRequestTheme create(MatchingRequest matchingRequest, TravelThemeType theme) {
        return new MatchingRequestTheme(matchingRequest, theme);
    }

    public TravelThemeType getTheme() {
        return theme;
    }
}
