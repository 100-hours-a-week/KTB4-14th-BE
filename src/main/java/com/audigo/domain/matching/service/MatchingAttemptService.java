package com.audigo.domain.matching.service;

import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.entity.MatchingAttempt;
import com.audigo.domain.matching.entity.MatchingAttemptCandidate;
import com.audigo.domain.matching.entity.MatchingAttemptStatus;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.MatchingRequestStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.matching.repository.MatchingAttemptRepository;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingAttemptService {

    private final MatchingRequestRepository matchingRequestRepository;
    private final MatchingAttemptRepository matchingAttemptRepository;
    private final MatchConnectionRepository matchConnectionRepository;

    public MatchingAttemptService(
            MatchingRequestRepository matchingRequestRepository,
            MatchingAttemptRepository matchingAttemptRepository,
            MatchConnectionRepository matchConnectionRepository
    ) {
        this.matchingRequestRepository = matchingRequestRepository;
        this.matchingAttemptRepository = matchingAttemptRepository;
        this.matchConnectionRepository = matchConnectionRepository;
    }

    @Transactional
    public AttemptPreparation prepareInitial(Long userId) {
        MatchingRequest request = findRestorableRequestForUpdate(userId);
        MatchingAttempt latest = findLatestSuccessful(request.getId());
        if (latest != null) {
            return AttemptPreparation.cached(request, storedCandidates(latest));
        }
        if (request.getStatus() != MatchingRequestStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        ensureNoAttemptInProgress(request.getId());
        MatchingAttempt attempt = matchingAttemptRepository.save(MatchingAttempt.start(request, 0));
        return AttemptPreparation.started(
                attempt.getId(),
                request,
                activeCounterpartIds(userId)
        );
    }

    @Transactional
    public AttemptPreparation prepareRematch(Long userId) {
        MatchingRequest request = findActiveRequestForUpdate(userId);
        if (!request.canRematch()) {
            throw new BusinessException(ErrorCode.MATCHING_REMATCH_LIMIT_EXCEEDED);
        }
        ensureNoAttemptInProgress(request.getId());
        MatchingAttempt latest = findLatestSuccessful(request.getId());
        if (latest == null) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }

        return startAttempt(request, request.getRetryCount() + 1, latest);
    }

    @Transactional
    public AttemptPreparation prepareRetry(Long userId) {
        MatchingRequest request = findActiveRequestForUpdate(userId);
        ensureNoAttemptInProgress(request.getId());
        MatchingAttempt failed = matchingAttemptRepository.findFirstByMatchingRequest_IdOrderByIdDesc(request.getId())
                .filter(attempt -> attempt.getStatus() == MatchingAttemptStatus.FAILED)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE));
        MatchingAttempt previous = findLatestSuccessful(request.getId());
        int retrySequence = previous == null ? 0 : request.getRetryCount() + 1;
        if (!request.canRematch() || failed.getRetrySequence() != retrySequence) {
            throw new BusinessException(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE);
        }
        return startAttempt(request, retrySequence, previous);
    }

    private AttemptPreparation startAttempt(MatchingRequest request, int retrySequence, MatchingAttempt previous) {
        Set<Long> excludedUserIds = new HashSet<>(activeCounterpartIds(request.getRequester().id()));
        if (previous != null) {
            previous.getCandidates().stream()
                    .map(MatchingAttemptCandidate::getCandidateUserId)
                    .forEach(excludedUserIds::add);
        }

        MatchingAttempt attempt = matchingAttemptRepository.save(
                MatchingAttempt.start(request, retrySequence)
        );
        return AttemptPreparation.started(attempt.getId(), request, excludedUserIds);
    }

    @Transactional
    public AttemptResult complete(Long attemptId, List<MatchingAttempt.CandidateScore> candidates) {
        // Lock the request before its attempt, as preparation and cancellation do.
        Long requestId = matchingAttemptRepository.findMatchingRequestIdById(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        MatchingRequest request = matchingRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        if (request.getStatus() != MatchingRequestStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        MatchingAttempt attempt = matchingAttemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        if (attempt.getStatus() != MatchingAttemptStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.MATCHING_RETRY_NOT_AVAILABLE);
        }
        attempt.succeed(candidates);
        if (attempt.getRetrySequence() > 0) {
            request.completeRematch();
        }
        return AttemptResult.from(request);
    }

    @Transactional
    public void fail(Long attemptId, RuntimeException cause) {
        Long requestId = matchingAttemptRepository.findMatchingRequestIdById(attemptId).orElse(null);
        if (requestId == null || matchingRequestRepository.findByIdForUpdate(requestId).isEmpty()) {
            return;
        }
        matchingAttemptRepository.findByIdForUpdate(attemptId)
                .filter(attempt -> attempt.getStatus() == MatchingAttemptStatus.PROCESSING)
                .ifPresent(attempt -> attempt.fail(cause.getMessage()));
    }

    private MatchingRequest findActiveRequestForUpdate(Long userId) {
        MatchingRequest request = findRestorableRequestForUpdate(userId);
        if (request.getStatus() != MatchingRequestStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        return request;
    }

    private MatchingRequest findRestorableRequestForUpdate(Long userId) {
        MatchingRequest request = matchingRequestRepository
                .findLatestByRequesterIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND));
        if (request.getStatus() != MatchingRequestStatus.ACTIVE
                && request.getStatus() != MatchingRequestStatus.EXHAUSTED) {
            throw new BusinessException(ErrorCode.MATCHING_REQUEST_NOT_FOUND);
        }
        request.getThemes().size();
        request.getRequester().id();
        return request;
    }

    private MatchingAttempt findLatestSuccessful(Long requestId) {
        return matchingAttemptRepository.findFirstByMatchingRequest_IdAndStatusOrderByIdDesc(
                        requestId,
                        MatchingAttemptStatus.SUCCEEDED
                )
                .orElse(null);
    }

    private void ensureNoAttemptInProgress(Long requestId) {
        if (matchingAttemptRepository.existsByMatchingRequest_IdAndStatus(
                requestId,
                MatchingAttemptStatus.PROCESSING
        )) {
            throw new BusinessException(ErrorCode.MATCHING_REMATCH_IN_PROGRESS);
        }
    }

    private Set<Long> activeCounterpartIds(Long userId) {
        return new HashSet<>(matchConnectionRepository.findCounterpartUserIds(
                userId,
                MatchConnectionStatus.ACTIVE
        ));
    }

    private List<StoredCandidate> storedCandidates(MatchingAttempt attempt) {
        return attempt.getCandidates().stream()
                .map(candidate -> new StoredCandidate(
                        candidate.getCandidateUserId(),
                        candidate.getMatchRate()
                ))
                .toList();
    }

    public record AttemptPreparation(
            Long attemptId,
            MatchingRequest matchingRequest,
            Set<Long> excludedUserIds,
            List<StoredCandidate> cachedCandidates
    ) {

        static AttemptPreparation started(
                Long attemptId,
                MatchingRequest matchingRequest,
                Set<Long> excludedUserIds
        ) {
            return new AttemptPreparation(
                    attemptId,
                    matchingRequest,
                    Set.copyOf(excludedUserIds),
                    List.of()
            );
        }

        static AttemptPreparation cached(
                MatchingRequest matchingRequest,
                List<StoredCandidate> cachedCandidates
        ) {
            return new AttemptPreparation(null, matchingRequest, Set.of(), List.copyOf(cachedCandidates));
        }

        public boolean cached() {
            return attemptId == null;
        }
    }

    public record StoredCandidate(Long userId, Integer matchRate) {
    }

    public record AttemptResult(int retryCount, int remainingRetryCount, boolean canRematch) {

        static AttemptResult from(MatchingRequest request) {
            return new AttemptResult(
                    request.getRetryCount(),
                    request.getRemainingRetryCount(),
                    request.canRematch()
            );
        }
    }
}
