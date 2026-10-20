package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    }

    @Test
    void failed_attempt_does_not_increment_retry_count() {
        MatchingRequest request = matchingRequest();
        MatchingAttempt attempt = MatchingAttempt.start(request, 1);
        ReflectionTestUtils.setField(attempt, "id", 40L);
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

    private void stubActiveRequest(MatchingRequest request) {
        when(matchingRequestRepository.findFirstByRequester_IdAndStatusInOrderByIdDesc(
                1L,
                List.of(MatchingRequestStatus.ACTIVE)
        )).thenReturn(Optional.of(request));
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
