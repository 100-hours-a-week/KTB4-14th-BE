package com.audigo.domain.travel.repository;

import com.audigo.domain.travel.entity.TravelGenerationProgress;
import com.audigo.domain.travel.entity.TravelGenerationStage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelGenerationProgressRepository extends JpaRepository<TravelGenerationProgress, Long> {

    List<TravelGenerationProgress> findAllByJobId(Long jobId);

    @Query("""
            select progress.stage as stage, progress.state as state
            from TravelGenerationProgress progress
            where progress.job.id = :jobId
            """)
    List<StageStateView> findAllStatesByJobId(@Param("jobId") Long jobId);

    Optional<TravelGenerationProgress> findByJobIdAndStage(Long jobId, TravelGenerationStage stage);

    interface StageStateView {
        TravelGenerationStage getStage();

        com.audigo.domain.travel.entity.TravelGenerationStageState getState();
    }
}
