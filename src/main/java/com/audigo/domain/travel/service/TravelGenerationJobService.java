package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.TravelGenerationStatusResponse;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelGenerationJobService {

    private static final List<TravelGenerationStage> REQUIRED_STAGES = List.of(
            TravelGenerationStage.PLACE_RECOMMEND,
            TravelGenerationStage.STAY_RECOMMEND,
            TravelGenerationStage.ROUTE_OPTIMIZE
    );

    private final TravelGenerationJobRepository jobRepository;
    private final TravelGenerationProgressStore progressStore;
    private final TravelItineraryPersistenceService itineraryPersistenceService;
    private final ObjectMapper objectMapper;

    public TravelGenerationJobService(
            TravelGenerationJobRepository jobRepository,
            TravelGenerationProgressStore progressStore,
            TravelItineraryPersistenceService itineraryPersistenceService,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.progressStore = progressStore;
        this.itineraryPersistenceService = itineraryPersistenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TravelGenerationJob findOwnedJob(Long userId, Long jobId) {
        return jobRepository.findByIdAndTravelPlanUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    @Transactional(readOnly = true)
    public TravelGenerationJob findLatestJob(Long userId, Long travelPlanId) {
        TravelGenerationJob job = jobRepository.findTopByTravelPlanIdOrderByIdDesc(travelPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!job.getTravelPlan().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return job;
    }

    @Transactional
    public void markStarted(Long jobId) {
        TravelGenerationJob job = getJob(jobId);
        if (job.getStatus() != TravelPlanStatus.GENERATING) {
            return;
        }
        job.markStarted();
        job.getTravelPlan().restartGeneration();
        jobRepository.save(job);
        progressStore.initialize(jobId);
        progressStore.update(jobId, TravelGenerationStage.PLACE_RECOMMEND,
                TravelGenerationStageState.RUNNING, null);
    }

    @Transactional
    public void handleEvent(Long jobId, AiGenerationEvent event) {
        TravelGenerationJob job = getJob(jobId);
        if (job.getStatus() != TravelPlanStatus.GENERATING) {
            return;
        }

        ResolvedEvent resolved = resolveEvent(event);
        if (resolved.kind() == EventKind.UNKNOWN && hasItineraryResult(event.data())) {
            markResultStagesDone(jobId, event.data());
            completeIfReady(job);
            return;
        }
        if (resolved.kind() == EventKind.HEARTBEAT || resolved.kind() == EventKind.UNKNOWN) {
            return;
        }
        if (resolved.kind() == EventKind.FAILURE) {
            markFailed(job, "AI 서버가 여행 생성에 실패했습니다.");
            return;
        }
        if (resolved.kind() == EventKind.COMPLETE) {
            markResultStagesDone(jobId, event.data());
            completeIfReady(job);
            return;
        }

        TravelGenerationStage stage = resolved.stage();
        if (!isStageOrderValid(jobId, stage)) {
            markFailed(job, "AI 생성 단계 순서가 올바르지 않습니다.");
            return;
        }
        if (resolved.state() == TravelGenerationStageState.RUNNING) {
            progressStore.update(jobId, stage, TravelGenerationStageState.RUNNING, event.data());
            return;
        }
        progressStore.update(jobId, stage, TravelGenerationStageState.DONE, event.data());
        if (stage == TravelGenerationStage.PLACE_RECOMMEND) {
            progressStore.update(jobId, TravelGenerationStage.STAY_RECOMMEND,
                    TravelGenerationStageState.RUNNING, null);
        }
        if (stage == TravelGenerationStage.STAY_RECOMMEND) {
            progressStore.update(jobId, TravelGenerationStage.ROUTE_OPTIMIZE,
                    TravelGenerationStageState.RUNNING, null);
        }
        completeIfReady(job);
    }

    @Transactional
    public void finishStream(Long jobId) {
        TravelGenerationJob job = getJob(jobId);
        if (job.getStatus() != TravelPlanStatus.GENERATING) {
            return;
        }
        completeIfReady(job);
        if (job.getStatus() == TravelPlanStatus.GENERATING) {
            markFailed(job, "AI 서버가 여행 생성 결과를 모두 보내지 않았습니다.");
        }
    }

    @Transactional
    public void markFailed(Long jobId, String message) {
        TravelGenerationJob job = getJob(jobId);
        if (job.getStatus() == TravelPlanStatus.COMPLETED) {
            return;
        }
        markFailed(job, message);
    }

    @Transactional(readOnly = true)
    public TravelGenerationStatusResponse status(Long jobId) {
        TravelGenerationJob job = getJob(jobId);
        return TravelGenerationStatusResponse.from(job, progressStore.states(job.getId()));
    }

    public boolean isGenerating(Long jobId) {
        return jobRepository.findById(jobId)
                .map(job -> job.getStatus() == TravelPlanStatus.GENERATING)
                .orElse(false);
    }

    private TravelGenerationJob getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private boolean isStageOrderValid(Long jobId, TravelGenerationStage stage) {
        Map<TravelGenerationStage, TravelGenerationStageState> states = progressStore.states(jobId);
        return switch (stage) {
            case PLACE_RECOMMEND, MUSIC_RECOMMEND -> true;
            case STAY_RECOMMEND -> states.get(TravelGenerationStage.PLACE_RECOMMEND)
                    == TravelGenerationStageState.DONE;
            case ROUTE_OPTIMIZE -> states.get(TravelGenerationStage.STAY_RECOMMEND)
                    == TravelGenerationStageState.DONE;
        };
    }

    private void completeIfReady(TravelGenerationJob job) {
        Map<TravelGenerationStage, TravelGenerationStageState> states = progressStore.states(job.getId());
        boolean ready = REQUIRED_STAGES.stream()
                .allMatch(stage -> states.get(stage) == TravelGenerationStageState.DONE);
        if (!ready) {
            return;
        }
        if (!itineraryPersistenceService.persistIfPresent(job)) {
            // 단계 이벤트가 모두 도착해도 최종 COMPLETE 이벤트가 안 올수있다
            // 스트림이 실제로 종료된 경우의 실패 처리는 finishStream()에서 담당
            return;
        }
        job.complete();
        job.getTravelPlan().markCompleted();
        jobRepository.save(job);
    }

    private void markResultStagesDone(Long jobId, String payload) {
        if (!hasItineraryResult(payload)) {
            return;
        }
        REQUIRED_STAGES.forEach(stage -> progressStore.update(
                jobId,
                stage,
                TravelGenerationStageState.DONE,
                payload
        ));
    }

    private boolean hasItineraryResult(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode result = root.get("result");
            if (result != null && result.isObject()) {
                root = result;
            }
            JsonNode days = root.get("days");
            return days != null && days.isArray() && !days.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void markFailed(TravelGenerationJob job, String message) {
        job.fail(message);
        job.getTravelPlan().markFailed();
        progressStore.update(job.getId(), firstIncompleteStage(job.getId()),
                TravelGenerationStageState.FAILED, null);
        jobRepository.save(job);
    }

    private TravelGenerationStage firstIncompleteStage(Long jobId) {
        Map<TravelGenerationStage, TravelGenerationStageState> states = progressStore.states(jobId);
        for (TravelGenerationStage stage : REQUIRED_STAGES) {
            if (states.get(stage) != TravelGenerationStageState.DONE) {
                return stage;
            }
        }
        return TravelGenerationStage.ROUTE_OPTIMIZE;
    }

    private ResolvedEvent resolveEvent(AiGenerationEvent event) {
        String type = event.normalizedType();
        if (!hasStageName(type) && event.data() != null && !event.data().isBlank()) {
            String dataStage = jsonValue(event.data(), "stage")
                    .or(() -> jsonValue(event.data(), "step"))
                    .or(() -> jsonValue(event.data(), "type"))
                    .orElse("")
                    .toUpperCase(Locale.ROOT);
            String dataStatus = jsonValue(event.data(), "status")
                    .or(() -> jsonValue(event.data(), "state"))
                    .orElse("")
                    .toUpperCase(Locale.ROOT);
            type = dataStage + "_" + dataStatus;
        }
        if (type.contains("HEARTBEAT") || type.equals("PING")) {
            return new ResolvedEvent(EventKind.HEARTBEAT, null, null);
        }
        if (type.contains("FAIL") || type.contains("ERROR")) {
            return new ResolvedEvent(EventKind.FAILURE, null, TravelGenerationStageState.FAILED);
        }
        if (type.contains("COMPLETE") || type.equals("DONE") || type.equals("SUCCEEDED")) {
            if (type.contains("PLACE") || type.contains("RESTAURANT")) {
                return stageEvent(TravelGenerationStage.PLACE_RECOMMEND, type);
            }
            if (type.contains("STAY") || type.contains("ACCOMMODATION")) {
                return stageEvent(TravelGenerationStage.STAY_RECOMMEND, type);
            }
            if (type.contains("ROUTE") || type.contains("TRANSIT")) {
                return stageEvent(TravelGenerationStage.ROUTE_OPTIMIZE, type);
            }
            if (type.contains("MUSIC")) {
                return stageEvent(TravelGenerationStage.MUSIC_RECOMMEND, type);
            }
            return new ResolvedEvent(EventKind.COMPLETE, null, TravelGenerationStageState.DONE);
        }
        if (type.contains("PLACE") || type.contains("RESTAURANT")) {
            return stageEvent(TravelGenerationStage.PLACE_RECOMMEND, type);
        }
        if (type.contains("STAY") || type.contains("ACCOMMODATION")) {
            return stageEvent(TravelGenerationStage.STAY_RECOMMEND, type);
        }
        if (type.contains("ROUTE") || type.contains("TRANSIT")) {
            return stageEvent(TravelGenerationStage.ROUTE_OPTIMIZE, type);
        }
        if (type.contains("MUSIC")) {
            return stageEvent(TravelGenerationStage.MUSIC_RECOMMEND, type);
        }
        return new ResolvedEvent(EventKind.UNKNOWN, null, null);
    }

    private boolean hasStageName(String type) {
        return type.contains("PLACE") || type.contains("RESTAURANT")
                || type.contains("STAY") || type.contains("ACCOMMODATION")
                || type.contains("ROUTE") || type.contains("TRANSIT")
                || type.contains("MUSIC") || type.contains("FAIL")
                || type.contains("ERROR") || type.contains("COMPLETE")
                || type.equals("DONE") || type.equals("SUCCEEDED");
    }

    private ResolvedEvent stageEvent(TravelGenerationStage stage, String type) {
        TravelGenerationStageState state = type.contains("START") || type.contains("RUNNING")
                ? TravelGenerationStageState.RUNNING
                : TravelGenerationStageState.DONE;
        return new ResolvedEvent(EventKind.STAGE, stage, state);
    }

    private Optional<String> jsonValue(String data, String field) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode value = root == null ? null : root.get(field);
            return value == null || value.isNull() ? Optional.empty() : Optional.ofNullable(value.asText());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private enum EventKind {
        STAGE,
        COMPLETE,
        FAILURE,
        HEARTBEAT,
        UNKNOWN
    }

    private record ResolvedEvent(EventKind kind, TravelGenerationStage stage, TravelGenerationStageState state) {
    }
}
