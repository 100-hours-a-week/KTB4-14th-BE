package com.audigo.global.migration.ai;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

// 상태 조회(08)용 AI executor 지표
@Component
public class AiExecutorMonitor {

    private final ThreadPoolTaskExecutor executor;
    private final AiJobTicketRegistry ticketRegistry;

    public AiExecutorMonitor(
            @Qualifier("travelGenerationExecutor") ThreadPoolTaskExecutor executor,
            AiJobTicketRegistry ticketRegistry
    ) {
        this.executor = executor;
        this.ticketRegistry = ticketRegistry;
    }

    public AiExecutorStats stats() {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        return new AiExecutorStats(
                pool.getActiveCount(),
                pool.getQueue().size(),
                pool.getPoolSize(),
                ticketRegistry.size()
        );
    }
}
