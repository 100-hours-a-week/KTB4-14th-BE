package com.audigo.domain.travel.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class TravelGenerationJobTest {

    @Test
    void 생성된_작업은_대기_상태이고_실행_시작_전에는_startedAt이_없다() throws Exception {
        TravelGenerationJob job = TravelGenerationJob.create(TravelPlan.create(
                1L,
                Region.create("서울특별시", "SEOUL"),
                java.time.LocalDateTime.of(2026, 10, 20, 10, 0),
                java.time.LocalDateTime.of(2026, 10, 22, 18, 0),
                2,
                CompanionType.FRIEND
        ));
        Method onCreate = TravelGenerationJob.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(job);

        assertThat(job.getExecutionStatus()).isEqualTo(TravelGenerationExecutionStatus.QUEUED);
        assertThat(job.getStartedAt()).isNull();

        job.markStarted();

        assertThat(job.getExecutionStatus()).isEqualTo(TravelGenerationExecutionStatus.PROCESSING);
        assertThat(job.getStartedAt()).isNotNull();
    }

    @Test
    void 완료와_실패는_각각_실행_상태에도_반영된다() {
        TravelGenerationJob completed = TravelGenerationJob.create(mockPlan());
        completed.markStarted();
        completed.complete();

        TravelGenerationJob failed = TravelGenerationJob.create(mockPlan());
        failed.markStarted();
        failed.fail("AI 응답 실패");

        assertThat(completed.getExecutionStatus()).isEqualTo(TravelGenerationExecutionStatus.COMPLETED);
        assertThat(failed.getExecutionStatus()).isEqualTo(TravelGenerationExecutionStatus.FAILED);
    }

    private TravelPlan mockPlan() {
        return org.mockito.Mockito.mock(TravelPlan.class);
    }
}
