package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.CreateMatchingRequestRequest;
import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.MatchingAttemptRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MatchingRequestServiceTest {

    @Mock
    private MatchingRequestRepository matchingRequestRepository;

    @Mock
    private MatchingAttemptRepository matchingAttemptRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MatchingRequestService matchingRequestService;

    @Test
    void creates_matching_request() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeUser()));
        when(matchingRequestRepository.existsByRequester_IdAndStatus(1L, MatchingRequestStatus.ACTIVE))
                .thenReturn(false);
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
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeUser()));
        when(matchingRequestRepository.existsByRequester_IdAndStatus(1L, MatchingRequestStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> matchingRequestService.create(1L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_ALREADY_ACTIVE)
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

    @Test
    void gets_my_matching_request() {
        MatchingRequest matchingRequest = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(matchingRequest, 0);
        attempt.succeed(List.of());
        when(matchingRequestRepository.findFirstByRequester_IdOrderByIdDesc(1L))
                .thenReturn(Optional.of(matchingRequest));
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(10L))
                .thenReturn(Optional.of(attempt));
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(10L, MatchingAttemptStatus.SUCCEEDED))
                .thenReturn(true);

        var response = matchingRequestService.getMine(1L);

        assertThat(response.preferredCompanionGender()).isEqualTo(PreferredCompanionGender.FEMALE);
        assertThat(response.themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(response.pace()).isEqualTo(TravelPaceType.BALANCED);
        assertThat(response.budgetMin()).isEqualTo(100_000);
        assertThat(response.budgetMax()).isEqualTo(800_000);
        assertThat(response.status()).isEqualTo(MatchingRequestStatus.ACTIVE);
        assertThat(response.latestAttemptStatus()).isEqualTo(MatchingAttemptStatus.SUCCEEDED);
        assertThat(response.retryCount()).isZero();
        assertThat(response.remainingRetryCount()).isEqualTo(3);
        assertThat(response.canRematch()).isTrue();
        assertThat(response.hasResult()).isTrue();
    }

    @Test
    void gets_exhausted_matching_request_for_result_recovery() {
        MatchingRequest matchingRequest = matchingRequest();
        matchingRequest.completeRematch();
        matchingRequest.completeRematch();
        matchingRequest.completeRematch();
        MatchingAttempt attempt = MatchingAttempt.start(matchingRequest, 3);
        attempt.succeed(List.of());
        when(matchingRequestRepository.findFirstByRequester_IdOrderByIdDesc(1L))
                .thenReturn(Optional.of(matchingRequest));
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(10L))
                .thenReturn(Optional.of(attempt));
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(10L, MatchingAttemptStatus.SUCCEEDED))
                .thenReturn(true);

        var response = matchingRequestService.getMine(1L);

        assertThat(response.status()).isEqualTo(MatchingRequestStatus.EXHAUSTED);
        assertThat(response.latestAttemptStatus()).isEqualTo(MatchingAttemptStatus.SUCCEEDED);
        assertThat(response.retryCount()).isEqualTo(3);
        assertThat(response.remainingRetryCount()).isZero();
        assertThat(response.canRematch()).isFalse();
        assertThat(response.hasResult()).isTrue();
    }

    @Test
    void rejects_get_when_my_matching_request_not_found() {
        when(matchingRequestRepository.findFirstByRequester_IdOrderByIdDesc(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchingRequestService.getMine(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
                );
    }

    @Test
    void rejects_get_when_latest_matching_request_is_terminal() {
        MatchingRequest matchingRequest = matchingRequest();
        matchingRequest.cancel();
        when(matchingRequestRepository.findFirstByRequester_IdOrderByIdDesc(1L))
                .thenReturn(Optional.of(matchingRequest));

        assertThatThrownBy(() -> matchingRequestService.getMine(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
                );
    }

    @Test
    void cancels_my_matching_request() {
        MatchingRequest matchingRequest = matchingRequest();
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(MatchingRequestStatus.ACTIVE)
        ))
                .thenReturn(Optional.of(matchingRequest));

        matchingRequestService.cancelMine(1L);

        assertThat(matchingRequest.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(matchingRequest.getEndedAt()).isNotNull();
        verify(matchingRequestRepository, never()).delete(any(MatchingRequest.class));
    }

    @Test
    void rejects_cancel_when_my_matching_request_not_found() {
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(MatchingRequestStatus.ACTIVE)
        ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchingRequestService.cancelMine(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
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

    private MatchingRequest matchingRequest() {
        MatchingRequest request = MatchingRequest.create(
                activeUser(),
                PreferredCompanionGender.FEMALE,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
        );
        ReflectionTestUtils.setField(request, "id", 10L);
        return request;
    }
}
