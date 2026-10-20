package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.CreateMatchConnectionRequest;
import com.audigo.domain.matching.dto.MatchConnectionResponse;
import com.audigo.domain.matching.dto.MatchingAiCandidateResponse;
import com.audigo.domain.matching.dto.MatchingAiCandidatesResponse;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingGender;
import com.audigo.domain.matching.entity.MatchingProfile;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.matching.repository.MatchingProfileRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MatchingCandidateServiceTest {

    @Mock
    private MatchingRequestRepository matchingRequestRepository;

    @Mock
    private MatchingProfileRepository matchingProfileRepository;

    @Mock
    private MatchingAiClient matchingAiClient;

    @Mock
    private MatchConnectionRepository matchConnectionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private MatchingAttemptService matchingAttemptService;

    @InjectMocks
    private MatchingCandidateService matchingCandidateService;

    @Test
    void gets_candidates_from_ai_with_my_matching_request() {
        MatchingRequest matchingRequest = matchingRequest();
        MatchingProfile candidateProfile = matchingProfile(22L);
        MatchingAiCandidatesResponse aiResponse = MatchingAiCandidatesResponse.of(List.of(
                new MatchingAiCandidateResponse(
                        22L,
                        89
                )
        ));
        when(matchingAttemptService.prepareInitial(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(40L, matchingRequest, Set.of())
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L)).thenReturn(List.of(candidateProfile));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenReturn(aiResponse);
        when(matchingAttemptService.complete(any(), any())).thenReturn(
                new MatchingAttemptService.AttemptResult(0, 3, true)
        );

        MatchingCandidatesResponse response = matchingCandidateService.getCandidates(1L);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.candidates().getFirst().nickname()).isEqualTo("매칭후보");
        assertThat(response.candidates().getFirst().profileImageUrl()).isNull();
        assertThat(response.candidates().getFirst().themes()).containsExactly(TravelThemeType.CULTURE);
        assertThat(response.candidates().getFirst().pace()).isEqualTo(TravelPaceType.RELAXED);
        assertThat(response.candidates().getFirst().matchRate()).isEqualTo(89);
        ArgumentCaptor<MatchingCandidateAiRequest> requestCaptor =
                ArgumentCaptor.forClass(MatchingCandidateAiRequest.class);
        verify(matchingAiClient).getCandidates(requestCaptor.capture());
        assertThat(requestCaptor.getValue().preferredCompanionGender()).isEqualTo(PreferredCompanionGender.FEMALE);
        assertThat(requestCaptor.getValue().themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(requestCaptor.getValue().pace()).isEqualTo(TravelPaceType.BALANCED);
        assertThat(requestCaptor.getValue().candidates()).hasSize(1);
        assertThat(requestCaptor.getValue().candidates().getFirst().userId()).isEqualTo(22L);
        assertThat(requestCaptor.getValue().candidates().getFirst().nickname()).isEqualTo("매칭후보");
        assertThat(requestCaptor.getValue().candidates().getFirst().gender()).isEqualTo(MatchingGender.MALE);
        assertThat(requestCaptor.getValue().candidates().getFirst().themes()).containsExactly(TravelThemeType.CULTURE);
    }

    @Test
    void rejects_candidates_when_matching_request_not_found() {
        when(matchingAttemptService.prepareInitial(1L))
                .thenThrow(new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));

        assertThatThrownBy(() -> matchingCandidateService.getCandidates(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
                );
    }

    @Test
    void rematches_with_only_eligible_candidates_and_returns_retry_metadata() {
        MatchingRequest matchingRequest = matchingRequest();
        MatchingProfile previousCandidate = matchingProfile(22L);
        MatchingProfile nextCandidate = matchingProfile(23L);
        when(matchingAttemptService.prepareRematch(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(
                        40L,
                        matchingRequest,
                        Set.of(22L)
                )
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L))
                .thenReturn(List.of(previousCandidate, nextCandidate));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenReturn(
                MatchingAiCandidatesResponse.of(List.of(new MatchingAiCandidateResponse(23L, 91)))
        );
        when(matchingAttemptService.complete(any(), any())).thenReturn(
                new MatchingAttemptService.AttemptResult(1, 2, true)
        );

        MatchingCandidatesResponse response = matchingCandidateService.rematch(1L);

        assertThat(response.candidates()).extracting(candidate -> candidate.userId())
                .containsExactly(23L);
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.remainingRetryCount()).isEqualTo(2);
        assertThat(response.canRematch()).isTrue();
        ArgumentCaptor<MatchingCandidateAiRequest> requestCaptor =
                ArgumentCaptor.forClass(MatchingCandidateAiRequest.class);
        verify(matchingAiClient).getCandidates(requestCaptor.capture());
        assertThat(requestCaptor.getValue().candidates())
                .extracting(candidate -> candidate.userId())
                .containsExactly(23L);
    }

    @Test
    void records_failed_attempt_without_completing_rematch() {
        MatchingRequest matchingRequest = matchingRequest();
        MatchingProfile candidateProfile = matchingProfile(22L);
        BusinessException aiFailure = new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        when(matchingAttemptService.prepareRematch(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(40L, matchingRequest, Set.of())
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L)).thenReturn(List.of(candidateProfile));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenThrow(aiFailure);

        assertThatThrownBy(() -> matchingCandidateService.rematch(1L))
                .isSameAs(aiFailure);
        verify(matchingAttemptService).fail(40L, aiFailure);
        verify(matchingAttemptService, never()).complete(any(), any());
    }

    @Test
    void records_profile_lookup_failure_so_attempt_does_not_stay_processing() {
        RuntimeException lookupFailure = new RuntimeException("profile lookup failed");
        when(matchingAttemptService.prepareInitial(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(40L, matchingRequest(), Set.of())
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L)).thenThrow(lookupFailure);

        assertThatThrownBy(() -> matchingCandidateService.getCandidates(1L)).isSameAs(lookupFailure);

        verify(matchingAttemptService).fail(40L, lookupFailure);
        verify(matchingAiClient, never()).getCandidates(any());
    }

    @Test
    void cached_result_lookup_failure_does_not_mark_previous_success_as_failed() {
        RuntimeException lookupFailure = new RuntimeException("profile lookup failed");
        when(matchingAttemptService.prepareInitial(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.cached(
                        matchingRequest(), List.of(new MatchingAttemptService.StoredCandidate(22L, 89))
                )
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L)).thenThrow(lookupFailure);

        assertThatThrownBy(() -> matchingCandidateService.getCandidates(1L)).isSameAs(lookupFailure);

        verify(matchingAttemptService, never()).fail(any(), any());
        verify(matchingAttemptService, never()).complete(any(), any());
    }

    @Test
    void retries_failed_attempt_and_returns_existing_response_contract() {
        when(matchingAttemptService.prepareRetry(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(41L, matchingRequest(), Set.of())
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L))
                .thenReturn(List.of(matchingProfile(22L)));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenReturn(
                MatchingAiCandidatesResponse.of(List.of(new MatchingAiCandidateResponse(22L, 89)))
        );
        when(matchingAttemptService.complete(any(), any())).thenReturn(
                new MatchingAttemptService.AttemptResult(0, 3, true)
        );

        MatchingCandidatesResponse response = matchingCandidateService.retry(1L);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.retryCount()).isZero();
        assertThat(response.remainingRetryCount()).isEqualTo(3);
        verify(matchingAttemptService).complete(41L, List.of(
                new MatchingAttempt.CandidateScore(22L, 89)
        ));
    }

    @Test
    void retry_failure_is_recorded_without_completing_or_changing_retry_count() {
        MatchingRequest request = matchingRequest();
        BusinessException aiFailure = new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        when(matchingAttemptService.prepareRetry(1L)).thenReturn(
                MatchingAttemptService.AttemptPreparation.started(41L, request, Set.of())
        );
        when(matchingProfileRepository.findActiveProfilesExceptUser(1L))
                .thenReturn(List.of(matchingProfile(22L)));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenThrow(aiFailure);

        assertThatThrownBy(() -> matchingCandidateService.retry(1L)).isSameAs(aiFailure);

        verify(matchingAttemptService).fail(41L, aiFailure);
        verify(matchingAttemptService, never()).complete(any(), any());
        assertThat(request.getRetryCount()).isZero();
    }

    @Test
    void responds_candidate_and_creates_match_connection() {
        MatchingRequest matchingRequest = matchingRequest();
        User targetUser = activeUser(22L, "매칭후보");
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(
                        MatchingRequestStatus.ACTIVE,
                        MatchingRequestStatus.EXHAUSTED,
                        MatchingRequestStatus.CONNECTED
                )
        )).thenReturn(Optional.of(matchingRequest));
        when(userRepository.findById(22L)).thenReturn(Optional.of(targetUser));
        when(matchConnectionRepository.findByMatchingRequest_IdAndTargetUser_IdAndStatus(
                10L,
                22L,
                MatchConnectionStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(matchConnectionRepository.save(any(MatchConnection.class)))
                .thenAnswer(invocation -> {
                    MatchConnection connection = invocation.getArgument(0);
                    ReflectionTestUtils.setField(connection, "id", 30L);
                    return connection;
                });

        MatchConnectionResponse response = matchingCandidateService.respondCandidate(
                1L,
                new CreateMatchConnectionRequest(22L)
        );

        assertThat(response.matchingRequestId()).isEqualTo(10L);
        assertThat(response.targetUserId()).isEqualTo(22L);
        assertThat(response.status()).isEqualTo(MatchConnectionStatus.ACTIVE);
        assertThat(matchingRequest.getStatus()).isEqualTo(MatchingRequestStatus.CONNECTED);
        assertThat(matchingRequest.getEndedAt()).isNotNull();
        verify(matchConnectionRepository).save(any(MatchConnection.class));
        verify(notificationService).notifyMatchSuccess(
                1L,
                30L,
                "매칭후보님과 매칭이 완료되었습니다."
        );
        verify(notificationService).notifyMatchSuccess(
                22L,
                30L,
                "윤혁님과 매칭이 완료되었습니다."
        );
    }

    @Test
    void responds_candidate_returns_existing_active_connection() {
        MatchingRequest matchingRequest = matchingRequest();
        User targetUser = activeUser(22L, "매칭후보");
        MatchConnection existing = MatchConnection.create(matchingRequest, targetUser);
        ReflectionTestUtils.setField(existing, "id", 30L);
        matchingRequest.markConnected();
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(
                        MatchingRequestStatus.ACTIVE,
                        MatchingRequestStatus.EXHAUSTED,
                        MatchingRequestStatus.CONNECTED
                )
        )).thenReturn(Optional.of(matchingRequest));
        when(userRepository.findById(22L)).thenReturn(Optional.of(targetUser));
        when(matchConnectionRepository.findByMatchingRequest_IdAndTargetUser_IdAndStatus(
                10L,
                22L,
                MatchConnectionStatus.ACTIVE
        )).thenReturn(Optional.of(existing));

        MatchConnectionResponse response = matchingCandidateService.respondCandidate(
                1L,
                new CreateMatchConnectionRequest(22L)
        );

        assertThat(response.matchConnectionId()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo(MatchConnectionStatus.ACTIVE);
        assertThat(matchingRequest.getStatus()).isEqualTo(MatchingRequestStatus.CONNECTED);
        verify(matchConnectionRepository, never()).save(any(MatchConnection.class));
        verify(notificationService, never()).notifyMatchSuccess(any(), any(), any());
    }

    @Test
    void rejects_response_when_matching_request_not_found() {
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(
                        MatchingRequestStatus.ACTIVE,
                        MatchingRequestStatus.EXHAUSTED,
                        MatchingRequestStatus.CONNECTED
                )
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchingCandidateService.respondCandidate(1L, new CreateMatchConnectionRequest(22L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
                );
    }

    @Test
    void rejects_response_when_target_user_is_self() {
        MatchingRequest matchingRequest = matchingRequest();
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(
                        MatchingRequestStatus.ACTIVE,
                        MatchingRequestStatus.EXHAUSTED,
                        MatchingRequestStatus.CONNECTED
                )
        )).thenReturn(Optional.of(matchingRequest));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L, "윤혁")));

        assertThatThrownBy(() -> matchingCandidateService.respondCandidate(1L, new CreateMatchConnectionRequest(1L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
                );
    }

    private MatchingRequest matchingRequest() {
        MatchingRequest matchingRequest = MatchingRequest.create(
                activeUser(1L, "윤혁"),
                PreferredCompanionGender.FEMALE,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
        );
        ReflectionTestUtils.setField(matchingRequest, "id", 10L);
        return matchingRequest;
    }

    private MatchingProfile matchingProfile(Long userId) {
        return MatchingProfile.create(
                activeUser(userId, "매칭후보"),
                true,
                MatchingGender.MALE,
                TravelPaceType.RELAXED,
                List.of(TravelThemeType.CULTURE)
        );
    }

    private User activeUser(Long id, String nickname) {
        User user = User.create(nickname, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
