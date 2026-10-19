package com.audigo.domain.matching.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingProfileTest {

    @Test
    void keeps_existing_theme_entities_when_same_themes_are_saved_again() {
        MatchingProfile profile = profileWith(TravelThemeType.NATURE, TravelThemeType.FOOD);
        List<MatchingProfileTheme> originalThemes = List.copyOf(profile.getThemes());

        profile.update(
                true,
                MatchingGender.FEMALE,
                TravelPaceType.BALANCED,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
        );

        assertThat(profile.getThemes()).containsExactlyElementsOf(originalThemes);
    }

    @Test
    void removes_missing_themes_and_adds_only_new_themes() {
        MatchingProfile profile = profileWith(TravelThemeType.NATURE, TravelThemeType.FOOD);
        MatchingProfileTheme retainedTheme = profile.getThemes().get(0);
        MatchingProfileTheme removedTheme = profile.getThemes().get(1);

        profile.update(
                true,
                MatchingGender.FEMALE,
                TravelPaceType.BALANCED,
                List.of(TravelThemeType.NATURE, TravelThemeType.CULTURE)
        );

        assertThat(profile.getThemes()).contains(retainedTheme);
        assertThat(profile.getThemes()).doesNotContain(removedTheme);
        assertThat(profile.getThemes())
                .extracting(MatchingProfileTheme::getTheme)
                .containsExactly(TravelThemeType.NATURE, TravelThemeType.CULTURE);
    }

    private MatchingProfile profileWith(TravelThemeType... themes) {
        return MatchingProfile.create(
                User.create("테스트사용자", null),
                true,
                MatchingGender.FEMALE,
                TravelPaceType.BALANCED,
                List.of(themes)
        );
    }
}
