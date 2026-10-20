package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.matching.repository.MatchingAttemptRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MatchingAttemptServiceTest {

    @Mock
    private MatchingRequestRepository matchingRequestRepository;

    @Mock
    private MatchingAttemptRepository matchingAttemptRepository;

    @Mock
    private MatchConnectionRepository matchConnectionRepository;

    @InjectMocks
    private MatchingAttemptService matchingAttemptService;

    @Test
    void prepares_rematch_excluding_previous_candidates_and_active_connections() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt previous = MatchingAttempt.start(request, 0);
        previous.succeed(List.of(
                new MatchingAttempt.CandidateScore(22L, 90),
                new MatchingAttempt.CandidateScore(23L, 80)
        ));
        stubActiveRequest(request);
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(
                10L,
                MatchingAttemptStatus.PROCESSING
        )).thenReturn(false);
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
                10L,
                MatchingAttemptStatus.SUCCEEDED
        )).thenReturn(Optional.of(previous));
        when(matchConnectionRepository.findCounterpartUserIds(1L, MatchConnectionStatus.ACTIVE))
                .thenReturn(List.of(30L));
        when(matchingAttemptRepository.save(any(MatchingAttempt.class))).thenAnswer(invocation -> {
            MatchingAttempt attempt = invocation.getArgument(0);
            ReflectionTestUtils.setField(attempt, "id", 40L);
            return attempt;
        });

        MatchingAttemptService.AttemptPreparation result = matchingAttemptService.prepareRematch(1L);

        assertThat(result.attemptId()).isEqualTo(40L);
        assertThat(result.excludedUserIds()).containsExactlyInAnyOrder(22L, 23L, 30L);
        ArgumentCaptor<MatchingAttempt> captor = ArgumentCaptor.forClass(MatchingAttempt.class);
        verify(matchingAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrySequence()).isEqualTo(1);
    }

    @Test
    void rejects_duplicate_rematch_while_attempt_is_processing() {
        MatchingRequest request = matchingRequest();
        stubActiveRequest(request);
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(
                10L,
                MatchingAttemptStatus.PROCESSING
        )).thenReturn(true);

        assertThatThrownBy(() -> matchingAttemptService.prepareRematch(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REMATCH_IN_PROGRESS)
                );
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void increments_retry_count_only_when_rematch_succeeds() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        ReflectionTestUtils.setField(attempt, "id", 40L);
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(attempt));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));

        MatchingAttemptService.AttemptResult result = matchingAttemptService.complete(
                40L,
                List.of(new MatchingAttempt.CandidateScore(22L, 90))
        );

        assertThat(request.getRetryCount()).isEqualTo(1);
        assertThat(result.retryCount()).isEqualTo(1);
        assertThat(result.remainingRetryCount()).isEqualTo(2);
        assertThat(result.canRematch()).isTrue();
        InOrder locks = inOrder(matchingRequestRepository, matchingAttemptRepository);
        locks.verify(matchingRequestRepository).findByIdForUpdate(10L);
        locks.verify(matchingAttemptRepository).findByIdForUpdate(40L);
    }

    @Test
    void failed_attempt_does_not_increment_retry_count() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        ReflectionTestUtils.setField(attempt, "id", 40L);
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(attempt));

        matchingAttemptService.fail(40L, new RuntimeException("AI timeout"));

        assertThat(attempt.getStatus()).isEqualTo(MatchingAttemptStatus.FAILED);
        assertThat(request.getRetryCount()).isZero();
    }

    @Test
    void restores_last_successful_candidates_from_exhausted_request() {
        MatchingRequest request = matchingRequest();
        request.completeRematch();
        request.completeRematch();
        request.completeRematch();
        MatchingAttempt previous = MatchingAttempt.start(request, 3);
        previous.succeed(List.of(new MatchingAttempt.CandidateScore(22L, 90)));
        when(matchingRequestRepository.findLatestByRequesterIdForUpdate(1L))
                .thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
                10L,
                MatchingAttemptStatus.SUCCEEDED
        )).thenReturn(Optional.of(previous));

        MatchingAttemptService.AttemptPreparation result = matchingAttemptService.prepareInitial(1L);

        assertThat(result.cached()).isTrue();
        assertThat(result.cachedCandidates()).containsExactly(
                new MatchingAttemptService.StoredCandidate(22L, 90)
        );
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void retries_failed_initial_attempt_in_same_request_without_spending_rematches() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt failed = MatchingAttempt.start(request, 0);
        failed.fail("AI timeout");
        stubActiveRequest(request);
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(10L))
                .thenReturn(Optional.of(failed));
        when(matchingAttemptRepository.save(any(MatchingAttempt.class))).thenAnswer(invocation -> {
            MatchingAttempt attempt = invocation.getArgument(0);
            ReflectionTestUtils.setField(attempt, "id", 41L);
            return attempt;
        });

        MatchingAttemptService.AttemptPreparation result = matchingAttemptService.prepareRetry(1L);

        assertThat(result.matchingRequest()).isSameAs(request);
        assertThat(result.attemptId()).isEqualTo(41L);
        assertThat(request.getRetryCount()).isZero();
        assertThat(failed.getStatus()).isEqualTo(MatchingAttemptStatus.FAILED);
        ArgumentCaptor<MatchingAttempt> captor = ArgumentCaptor.forClass(MatchingAttempt.class);
        verify(matchingAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrySequence()).isZero();
    }

    @Test
    void retries_failed_rematch_using_last_successful_exclusions_and_same_sequence() {
        MatchingRequest request = matchingRequest();
        request.completeRematch();
        MatchingAttempt previous = MatchingAttempt.start(request, 1);
        previous.succeed(List.of(new MatchingAttempt.CandidateScore(22L, 90)));
        MatchingAttempt failed = MatchingAttempt.start(request, 2);
        failed.fail("AI timeout");
        stubActiveRequest(request);
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(10L))
                .thenReturn(Optional.of(failed));
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
                10L, MatchingAttemptStatus.SUCCEEDED
        )).thenReturn(Optional.of(previous));
        when(matchConnectionRepository.findCounterpartUserIds(1L, MatchConnectionStatus.ACTIVE))
                .thenReturn(List.of(30L));
        when(matchingAttemptRepository.save(any(MatchingAttempt.class))).thenAnswer(invocation -> {
            MatchingAttempt attempt = invocation.getArgument(0);
            ReflectionTestUtils.setField(attempt, "id", 41L);
            return attempt;
        });

        MatchingAttemptService.AttemptPreparation result = matchingAttemptService.prepareRetry(1L);

        assertThat(result.excludedUserIds()).containsExactlyInAnyOrder(22L, 30L);
        assertThat(request.getRetryCount()).isEqualTo(1);
        assertThat(previous.getCandidates()).extracting(candidate -> candidate.getCandidateUserId())
                .containsExactly(22L);
        ArgumentCaptor<MatchingAttempt> captor = ArgumentCaptor.forClass(MatchingAttempt.class);
        verify(matchingAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrySequence()).isEqualTo(2);
    }

    @Test
    void rejects_retry_while_an_attempt_is_processing() {
        stubActiveRequest(matchingRequest());
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(10L, MatchingAttemptStatus.PROCESSING))
                .thenReturn(true);

        assertThatThrownBy(() -> matchingAttemptService.prepareRetry(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REMATCH_IN_PROGRESS));
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void rejects_duplicate_initial_attempt_while_first_attempt_is_processing() {
        stubActiveRequest(matchingRequest());
        when(matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(10L, MatchingAttemptStatus.PROCESSING))
                .thenReturn(true);

        assertThatThrownBy(() -> matchingAttemptService.prepareInitial(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REMATCH_IN_PROGRESS));
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void rejects_retry_when_latest_attempt_succeeded_with_zero_candidates() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt previous = MatchingAttempt.start(request, 0);
        previous.succeed(List.of());
        stubActiveRequest(request);
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(10L))
                .thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> matchingAttemptService.prepareRetry(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE));
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void rejects_retry_of_cancelled_session() {
        MatchingRequest request = matchingRequest();
        request.cancel();
        stubActiveRequest(request);

        assertThatThrownBy(() -> matchingAttemptService.prepareRetry(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void rejects_retry_without_any_previous_attempt() {
        stubActiveRequest(matchingRequest());

        assertThatThrownBy(() -> matchingAttemptService.prepareRetry(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE));
        verify(matchingAttemptRepository, never()).save(any());
    }

    @Test
    void rejects_late_initial_result_after_cancellation_without_saving_candidates() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 0);
        request.cancel();
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> matchingAttemptService.complete(
                40L, List.of(new MatchingAttempt.CandidateScore(22L, 90))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND));

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(request.getRetryCount()).isZero();
        assertThat(attempt.getCandidates()).isEmpty();
        verify(matchingAttemptRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void cancellation_then_late_rematch_result_preserves_failure_and_previous_candidates() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt previous = MatchingAttempt.start(request, 0);
        previous.succeed(List.of(new MatchingAttempt.CandidateScore(22L, 90)));
        MatchingAttempt processing = MatchingAttempt.start(request, 1);
        stubActiveRequest(request);
        when(matchingAttemptRepository.findAllByMatchingRequest_IdAndStatus(10L, MatchingAttemptStatus.PROCESSING))
                .thenReturn(List.of(processing));
        MatchingRequestService requestService = new MatchingRequestService(
                matchingRequestRepository, matchingAttemptRepository, null
        );

        requestService.cancelMine(1L);

        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> matchingAttemptService.complete(
                40L, List.of(new MatchingAttempt.CandidateScore(23L, 85))
        )).isInstanceOf(BusinessException.class);

        assertThat(processing.getStatus()).isEqualTo(MatchingAttemptStatus.FAILED);
        assertThat(processing.getCandidates()).isEmpty();
        assertThat(previous.getCandidates()).extracting(candidate -> candidate.getCandidateUserId())
                .containsExactly(22L);
        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(request.getRetryCount()).isZero();
    }

    @Test
    void result_completed_before_cancel_can_be_cancelled_without_rewriting_attempt() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(attempt));
        matchingAttemptService.complete(40L, List.of(new MatchingAttempt.CandidateScore(22L, 90)));

        stubActiveRequest(request);
        MatchingRequestService requestService = new MatchingRequestService(
                matchingRequestRepository, matchingAttemptRepository, null
        );
        requestService.cancelMine(1L);

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(attempt.getStatus()).isEqualTo(MatchingAttemptStatus.SUCCEEDED);
        assertThat(attempt.getCandidates()).hasSize(1);
        assertThat(request.getRetryCount()).isEqualTo(1);
    }

    @Test
    void late_failure_does_not_overwrite_successful_attempt() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        attempt.succeed(List.of(new MatchingAttempt.CandidateScore(22L, 90)));
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(attempt));

        matchingAttemptService.fail(40L, new RuntimeException("late failure"));

        assertThat(attempt.getStatus()).isEqualTo(MatchingAttemptStatus.SUCCEEDED);
        assertThat(attempt.getCandidates()).hasSize(1);
    }

    @Test
    void duplicate_completion_does_not_increment_retry_count_twice() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(attempt));
        List<MatchingAttempt.CandidateScore> scores = List.of(new MatchingAttempt.CandidateScore(22L, 90));
        matchingAttemptService.complete(40L, scores);

        assertThatThrownBy(() -> matchingAttemptService.complete(40L, scores))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE));

        assertThat(request.getRetryCount()).isEqualTo(1);
        assertThat(attempt.getCandidates()).hasSize(1);
    }

    @Test
    void restores_previous_success_after_rematch_failed_without_starting_new_ai_work() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt previous = MatchingAttempt.start(request, 0);
        previous.succeed(List.of(new MatchingAttempt.CandidateScore(22L, 90)));
        MatchingAttempt failed = MatchingAttempt.start(request, 1);
        when(matchingAttemptRepository.findMatchingRequestIdById(40L)).thenReturn(Optional.of(10L));
        when(matchingRequestRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(request));
        when(matchingAttemptRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(failed));
        matchingAttemptService.fail(40L, new RuntimeException("AI timeout"));
        stubActiveRequest(request);
        when(matchingAttemptRepository.findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
                10L, MatchingAttemptStatus.SUCCEEDED
        )).thenReturn(Optional.of(previous));

        MatchingAttemptService.AttemptPreparation result = matchingAttemptService.prepareInitial(1L);

        assertThat(result.cached()).isTrue();
        assertThat(failed.getStatus()).isEqualTo(MatchingAttemptStatus.FAILED);
        assertThat(result.cachedCandidates()).containsExactly(new MatchingAttemptService.StoredCandidate(22L, 90));
        assertThat(request.getRemainingRetryCount()).isEqualTo(3);
        verify(matchingAttemptRepository, never()).save(any());
    }

    private void stubActiveRequest(MatchingRequest request) {
        when(matchingRequestRepository.findLatestByRequesterIdForUpdate(1L))
                .thenReturn(Optional.of(request));
    }

    private MatchingRequest matchingRequest() {
        User user = User.create("요청자", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        MatchingRequest request = MatchingRequest.create(
                user,
                PreferredCompanionGender.ANY,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE)
        );
        ReflectionTestUtils.setField(request, "id", 10L);
        return request;
    }
}
