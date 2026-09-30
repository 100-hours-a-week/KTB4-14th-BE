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
