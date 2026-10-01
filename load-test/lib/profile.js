function positiveInteger(name, fallback) {
    const value = Number(__ENV[name] || fallback);
    if (!Number.isInteger(value) || value <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

export function duration(name, fallback) {
    const value = __ENV[name] || fallback;
    if (!/^\d+(ms|s|m|h)$/.test(value)) {
        throw new Error(`${name} must be a k6 duration such as 30s or 5m`);
    }
    return value;
}

// 월간 요청량에서 계산한 소수 RPS는 분 단위 요청률로 표현한다.
// 예: 38 req/min = 약 0.633 RPS.
export function arrivalRate(name, fallback) {
    return positiveInteger(name, fallback);
}

export function vus(name, fallback) {
    return positiveInteger(name, fallback);
}

export function requireWriteConfirmation() {
    if (__ENV.ALLOW_WRITE_TESTS !== 'true') {
        throw new Error('ALLOW_WRITE_TESTS=true is required for mutating scenarios');
    }
}

export function requireExternalSmokeConfirmation() {
    if (__ENV.CONFIRM_EXTERNAL_SMOKE !== 'true') {
        throw new Error('CONFIRM_EXTERNAL_SMOKE=true is required before a real AI/Kakao smoke request');
    }
}

// P-01은 여행 생성이 실제 AI가 아닌 Staging AI Mock으로 라우팅됐음을 실행자가 명시해야 한다.
// 이 플래그는 Mock 라우팅을 구성하거나 검증하지 않으며, 실제 설정 확인은 배포 단계의 책임이다.
export function requireAiMockConfirmation() {
    if (__ENV.CONFIRM_AI_MOCK !== 'true') {
        throw new Error('CONFIRM_AI_MOCK=true is required for the P-01 full-generation scenario');
    }
}
