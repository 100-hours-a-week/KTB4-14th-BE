import { arrivalRate, duration, vus } from './profile.js';

// HTTP API 하나를 직접 호출하는 시나리오의 공통 Ramp 설정이다.
// `timeUnit: 1s`이므로 각 rate 환경변수는 flow/min이 아니라 HTTP RPS다.
export function rampingArrivalScenario({ name, prefix, defaults = {} }) {
    const startRate = arrivalRate(`${prefix}_START_RPS`, defaults.startRate ?? 1);
    const peakRate = arrivalRate(`${prefix}_PEAK_RPS`, defaults.peakRate ?? 50);
    const preAllocatedVUs = vus(`${prefix}_PRE_ALLOCATED_VUS`, defaults.preAllocatedVUs ?? 10);
    const maxVUs = vus(`${prefix}_MAX_VUS`, defaults.maxVUs ?? 100);

    if (maxVUs < preAllocatedVUs) throw new Error(`${prefix}_MAX_VUS must be >= ${prefix}_PRE_ALLOCATED_VUS`);

    return {
        maxVUs,
        options: {
            scenarios: {
                [name]: {
                    executor: 'ramping-arrival-rate',
                    startRate,
                    timeUnit: '1s',
                    preAllocatedVUs,
                    maxVUs,
                    stages: [
                        {
                            target: arrivalRate(`${prefix}_STAGE_1_RPS`, defaults.stage1Rate ?? 10),
                            duration: duration(`${prefix}_STAGE_1_DURATION`, defaults.stage1Duration ?? '30s'),
                        },
                        {
                            target: arrivalRate(`${prefix}_STAGE_2_RPS`, defaults.stage2Rate ?? 30),
                            duration: duration(`${prefix}_STAGE_2_DURATION`, defaults.stage2Duration ?? '30s'),
                        },
                        {
                            target: peakRate,
                            duration: duration(`${prefix}_UP_DURATION`, defaults.upDuration ?? '30s'),
                        },
                        {
                            target: peakRate,
                            duration: duration(`${prefix}_PEAK_DURATION`, defaults.peakDuration ?? '2m'),
                        },
                        {
                            target: startRate,
                            duration: duration(`${prefix}_DOWN_DURATION`, defaults.downDuration ?? '30s'),
                        },
                    ],
                },
            },
        },
    };
}
