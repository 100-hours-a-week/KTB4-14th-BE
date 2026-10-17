package com.audigo.global.migration.task;

import com.audigo.global.migration.gate.Admission;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.tracking.WorkTicket;
import com.audigo.global.migration.tracking.WorkType;
import java.util.concurrent.atomic.AtomicLong;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 모든 @Scheduled 실행을 Gate로 판정한다. OPEN이 아니거나 AI 시작 지연 중이면 이번 회차를 건너뛰고,
// 실행하는 회차는 SCHEDULED 티켓으로 끝날 때까지 추적한다. 애너테이션 기반이라 새 @Scheduled에도 자동 적용된다
@Aspect
@Component
public class ScheduledTaskGuardAspect {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskGuardAspect.class);

    private final MigrationWriteGate gate;
    private final AtomicLong skippedRuns = new AtomicLong();

    public ScheduledTaskGuardAspect(MigrationWriteGate gate) {
        this.gate = gate;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)"
            + " || @annotation(org.springframework.scheduling.annotation.Schedules)")
    public Object guard(ProceedingJoinPoint joinPoint) throws Throwable {
        String description = describe(joinPoint);
        Admission admission = gate.tryAdmit(WorkType.SCHEDULED, description);
        if (admission instanceof Admission.Admitted admitted) {
            try (WorkTicket ignored = admitted.ticket()) {
                return joinPoint.proceed();
            }
        }
        long skipped = skippedRuns.incrementAndGet();
        log.debug("migration_scheduled_skipped task={} skipped_total={}", description, skipped);
        // @Scheduled 메서드는 void만 허용되므로 반환값이 없다
        return null;
    }

    // 인스턴스 기동 이후 건너뛴 회차 합계
    public long skippedRuns() {
        return skippedRuns.get();
    }

    private static String describe(ProceedingJoinPoint joinPoint) {
        return "scheduled:" + joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
    }
}
