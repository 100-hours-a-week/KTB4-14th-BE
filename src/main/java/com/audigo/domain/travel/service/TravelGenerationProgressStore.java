package com.audigo.domain.travel.service;

import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelGenerationProgress;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import com.audigo.domain.travel.entity.TravelGenerationStageState;
import com.audigo.domain.travel.repository.TravelGenerationJobRepository;
import com.audigo.domain.travel.repository.TravelGenerationProgressRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// SSE 단계 진행 상태와 일정 저장에 사용할 원문 결과를 DB에 보관
@Component
public class TravelGenerationProgressStore {

    private final TravelGenerationProgressRepository progressRepository;
    private final TravelGenerationJobRepository jobRepository;

    public TravelGenerationProgressStore(
            TravelGenerationProgressRepository progressRepository,
            TravelGenerationJobRepository jobRepository
    ) {
        this.progressRepository = progressRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void initialize(Long jobId) {
        TravelGenerationJob job = getJob(jobId);
        EnumSet<TravelGenerationStage> existingStages = progressRepository.findAllByJobId(jobId).stream()
                .map(TravelGenerationProgress::getStage)
                .collect(() -> EnumSet.noneOf(TravelGenerationStage.class), EnumSet::add, EnumSet::addAll);
        List<TravelGenerationProgress> missing = java.util.Arrays.stream(TravelGenerationStage.values())
                .filter(stage -> !existingStages.contains(stage))
                .map(stage -> TravelGenerationProgress.create(job, stage))
                .toList();
        if (!missing.isEmpty()) {
            progressRepository.saveAll(missing);
        }
    }

    @Transactional
    public void update(Long jobId, TravelGenerationStage stage, TravelGenerationStageState state, String payload) {
        TravelGenerationProgress progress = progressRepository.findByJobIdAndStage(jobId, stage)
                .orElseGet(() -> TravelGenerationProgress.create(getJob(jobId), stage));
        progress.update(state, payload);
        progressRepository.save(progress);
    }

    @Transactional(readOnly = true)
    public Map<TravelGenerationStage, TravelGenerationStageState> states(Long jobId) {
        Map<TravelGenerationStage, TravelGenerationStageState> states =
                new EnumMap<>(TravelGenerationStage.class);
        progressRepository.findAllStatesByJobId(jobId)
                .forEach(progress -> states.put(progress.getStage(), progress.getState()));
        return Collections.unmodifiableMap(states);
    }

    @Transactional(readOnly = true)
    public String payload(Long jobId, TravelGenerationStage stage) {
        return progressRepository.findByJobIdAndStage(jobId, stage)
                .map(TravelGenerationProgress::getPayload)
                .orElse(null);
    }

    private TravelGenerationJob getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }
}
