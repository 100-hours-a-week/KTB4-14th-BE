package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.UpdateMatchingProfileRequest;
import com.audigo.domain.matching.entity.MatchingGender;
import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.repository.MatchingProfileRepository;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchingProfileServiceTest {

    @Mock
    private MatchingProfileRepository matchingProfileRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MatchingProfileService matchingProfileService;

    @Test
    void returns_empty_profile_when_user_has_no_matching_profile() {
        when(matchingProfileRepository.findByUser_Id(1L)).thenReturn(Optional.empty());

        var response = matchingProfileService.getMyProfile(1L);

        assertThat(response.exists()).isFalse();
        assertThat(response.active()).isFalse();
        assertThat(response.gender()).isNull();
        assertThat(response.pace()).isNull();
        assertThat(response.themes()).isEmpty();
        assertThat(response.complete()).isFalse();
        assertThat(response.canMatch()).isFalse();
    }

    @Test
    void creates_active_matching_profile() {
        when(matchingProfileRepository.findByUser_Id(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(matchingProfileRepository.save(any(MatchingProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = matchingProfileService.updateMyProfile(
                1L,
                new UpdateMatchingProfileRequest(
                        true,
                        MatchingGender.FEMALE,
                        TravelPaceType.BALANCED,
                        List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
                )
        );

        assertThat(response.exists()).isTrue();
        assertThat(response.active()).isTrue();
        assertThat(response.gender()).isEqualTo(MatchingGender.FEMALE);
        assertThat(response.pace()).isEqualTo(TravelPaceType.BALANCED);
        assertThat(response.themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(response.complete()).isTrue();
        assertThat(response.canMatch()).isTrue();
    }

    @Test
    void updates_existing_matching_profile_to_inactive() {
        MatchingProfile profile = MatchingProfile.create(
                activeUser(),
                true,
                MatchingGender.MALE,
                TravelPaceType.PACKED,
                List.of(TravelThemeType.ACTIVITY)
        );
        when(matchingProfileRepository.findByUser_Id(1L)).thenReturn(Optional.of(profile));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(matchingProfileRepository.save(any(MatchingProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = matchingProfileService.updateMyProfile(
                1L,
                new UpdateMatchingProfileRequest(false, MatchingGender.MALE, TravelPaceType.RELAXED, List.of())
        );

        assertThat(response.exists()).isTrue();
        assertThat(response.active()).isFalse();
        assertThat(response.gender()).isEqualTo(MatchingGender.MALE);
        assertThat(response.pace()).isEqualTo(TravelPaceType.RELAXED);
        assertThat(response.themes()).isEmpty();
        assertThat(response.complete()).isFalse();
        assertThat(response.canMatch()).isFalse();
    }

    @Test
    void rejects_active_profile_without_theme() {
        assertThatThrownBy(() -> matchingProfileService.updateMyProfile(
                1L,
                new UpdateMatchingProfileRequest(true, MatchingGender.FEMALE, TravelPaceType.BALANCED, List.of())
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    @Test
    void rejects_duplicated_themes() {
        assertThatThrownBy(() -> matchingProfileService.updateMyProfile(
                1L,
                new UpdateMatchingProfileRequest(
                        true,
                        MatchingGender.FEMALE,
                        TravelPaceType.BALANCED,
                        List.of(TravelThemeType.NATURE, TravelThemeType.NATURE)
                )
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    private User activeUser() {
        return User.create("윤혁", null);
    }
}
