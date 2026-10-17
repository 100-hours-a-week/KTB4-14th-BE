package com.audigo.global.migration.ai;

import java.util.List;

// 쓰기 단계가 OPEN에 도달해 지연된 AI 작업을 실행할 수 있게 됐다. 지연된 순서대로 담긴다
public record DeferredAiJobsReleasedEvent(List<ReleasedAiJob> jobs) {

    public DeferredAiJobsReleasedEvent {
        jobs = List.copyOf(jobs);
    }
}
