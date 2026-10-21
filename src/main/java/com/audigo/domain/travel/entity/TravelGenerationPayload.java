package com.audigo.domain.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "ai_generation_job_payloads")
public class TravelGenerationPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_job_id", nullable = false, unique = true)
    private TravelGenerationJob job;

    @Column(name = "request_payload", nullable = false, columnDefinition = "LONGTEXT")
    private String requestPayload;

    @Column(name = "result_payload", columnDefinition = "LONGTEXT")
    private String resultPayload;

    protected TravelGenerationPayload() {
    }

    private TravelGenerationPayload(TravelGenerationJob job, String requestPayload) {
        this.job = Objects.requireNonNull(job, "여행 생성 작업은 필수입니다.");
        this.requestPayload = requirePayload(requestPayload, "AI 요청 스냅샷은 비어 있을 수 없습니다.");
    }

    public static TravelGenerationPayload create(TravelGenerationJob job, String requestPayload) {
        return new TravelGenerationPayload(job, requestPayload);
    }

    public void replaceRequest(String requestPayload) {
        this.requestPayload = requirePayload(requestPayload, "AI 요청 스냅샷은 비어 있을 수 없습니다.");
    }

    public void saveResult(String resultPayload) {
        this.resultPayload = requirePayload(resultPayload, "AI 결과 스냅샷은 비어 있을 수 없습니다.");
    }

    private static String requirePayload(String payload, String message) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return payload;
    }

    public Long getId() {
        return id;
    }

    public TravelGenerationJob getJob() {
        return job;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public String getResultPayload() {
        return resultPayload;
    }
}
