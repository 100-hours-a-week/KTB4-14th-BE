package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.CreateMatchingRequestRequest;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
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
class MatchingRequestServiceTest {

    @Mock
    private MatchingRequestRepository matchingRequestRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MatchingRequestService matchingRequestService;

    @Test
    void creates_matching_request() {
        when(matchingRequestRepository.existsByRequester_Id(1L)).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(matchingRequestRepository.save(any(MatchingRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = matchingRequestService.create(1L, validRequest());

        assertThat(response.preferredCompanionGender()).isEqualTo(PreferredCompanionGender.FEMALE);
        assertThat(response.themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(response.pace()).isEqualTo(TravelPaceType.BALANCED);
        assertThat(response.budgetMin()).isEqualTo(100_000);
        assertThat(response.budgetMax()).isEqualTo(800_000);
    }

    @Test
    void rejects_request_when_user_already_has_current_matching_request() {
        when(matchingRequestRepository.existsByRequester_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> matchingRequestService.create(1L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    @Test
    void rejects_empty_themes() {
        assertThatThrownBy(() -> matchingRequestService.create(
                1L,
                new CreateMatchingRequestRequest(
                        PreferredCompanionGender.ANY,
                        List.of(),
                        TravelPaceType.RELAXED,
                        null,
                        null
                )
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    @Test
    void rejects_duplicated_themes() {
        assertThatThrownBy(() -> matchingRequestService.create(
                1L,
                new CreateMatchingRequestRequest(
                        PreferredCompanionGender.ANY,
                        List.of(TravelThemeType.NATURE, TravelThemeType.NATURE),
                        TravelPaceType.RELAXED,
                        null,
                        null
                )
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    @Test
    void rejects_invalid_budget_range() {
        assertThatThrownBy(() -> matchingRequestService.create(
                1L,
                new CreateMatchingRequestRequest(
                        PreferredCompanionGender.MALE,
                        List.of(TravelThemeType.SNS),
                        TravelPaceType.PACKED,
                        900_000,
                        100_000
                )
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    private CreateMatchingRequestRequest validRequest() {
        return new CreateMatchingRequestRequest(
                PreferredCompanionGender.FEMALE,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD),
                TravelPaceType.BALANCED,
                100_000,
                800_000
        );
    }

    private User activeUser() {
        return User.create("윤혁", null);
    }
}
