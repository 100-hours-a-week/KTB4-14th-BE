import { runCompletionFlow } from '../completion/flow.js';
import { runItineraryReadFlow } from '../itinerary/flow.js';
import { runSseFlow } from '../sse/flow.js';

import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';

function durationMilliseconds(value) {
    const match = /^(\d+)(ms|s|m|h)$/.exec(value);
    const amount = Number(match[1]);
    return amount * ({ ms: 1, s: 1_000, m: 60_000, h: 3_600_000 }[match[2]]);
}

// 읽기와 완료 Ramp의 rate·stage 시간은 독립적으로 설정한다. 다만 혼합 프로파일에서는
// 두 stage 총 시간이 같아야 같은 시간축에서 스파이크를 재현할 수 있다.
function profile(prefix, defaults) {
    const start = arrivalRate(`${prefix}_START_RATE_PER_MINUTE`, defaults.start);
    const peak = arrivalRate(`${prefix}_PEAK_RATE_PER_MINUTE`, defaults.peak);
    const durations = [
        duration(`${prefix}_STAGE_1_DURATION`, '1m'),
        duration(`${prefix}_STAGE_2_DURATION`, '1m'),
        duration(`${prefix}_UP_DURATION`, '1m'),
        duration(`${prefix}_PEAK_DURATION`, '5m'),
        duration(`${prefix}_DOWN_DURATION`, '1m'),
    ];
    return {
        start,
        totalMs: durations.reduce((total, value) => total + durationMilliseconds(value), 0),
        stages: [
            { target: arrivalRate(`${prefix}_STAGE_1_RATE_PER_MINUTE`, defaults.stage1), duration: durations[0] },
            { target: arrivalRate(`${prefix}_STAGE_2_RATE_PER_MINUTE`, defaults.stage2), duration: durations[1] },
            { target: peak, duration: durations[2] },
            { target: peak, duration: durations[3] },
            { target: start, duration: durations[4] },
        ],
    };
}

const itinerary = profile('P02_ITINERARY_RAMP', { start: 13, stage1: 26, stage2: 52, peak: 100 });
const completion = profile('P02_COMPLETION_RAMP', { start: 1, stage1: 2, stage2: 5, peak: 10 });
if (itinerary.totalMs !== completion.totalMs) {
    throw new Error(
        `P-02 itinerary and completion ramp durations must have the same total: ${itinerary.totalMs}ms vs ${completion.totalMs}ms`
    );
}
const rampDuration = `${itinerary.totalMs}ms`;
if (__ENV.P02_RAMP_SSE_DURATION && duration('P02_RAMP_SSE_DURATION', rampDuration) !== rampDuration) {
    throw new Error(`P02_RAMP_SSE_DURATION must equal LT-01/LT-02 ramp total (${rampDuration})`);
}

const sseConnections = vus('P02_RAMP_SSE_CONNECTIONS', 10);
if (sseConnections > testUserCount()) {
    throw new Error(`P02_RAMP_SSE_CONNECTIONS (${sseConnections}) exceeds configured test users (${testUserCount()})`);
}

export const options = {
    scenarios: {
        p02_itinerary_read_ramp: {
            executor: 'ramping-arrival-rate',
            exec: 'runItineraryRead',
            startRate: itinerary.start,
            timeUnit: '1m',
            preAllocatedVUs: vus('P02_ITINERARY_RAMP_PRE_ALLOCATED_VUS', 5),
            maxVUs: vus('P02_ITINERARY_RAMP_MAX_VUS', 100),
            stages: itinerary.stages,
        },
        p02_completion_ramp: {
            executor: 'ramping-arrival-rate',
            exec: 'runCompletion',
            startRate: completion.start,
            timeUnit: '1m',
            preAllocatedVUs: vus('P02_COMPLETION_RAMP_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('P02_COMPLETION_RAMP_MAX_VUS', 20),
            stages: completion.stages,
        },
        p02_sse: {
            executor: 'constant-vus',
            exec: 'runSse',
            vus: sseConnections,
            duration: rampDuration,
            gracefulStop: '0s',
        },
    },
};

export function runItineraryRead() { runItineraryReadFlow(); }
export function runCompletion() { runCompletionFlow(); }
export function runSse() { runSseFlow(); }
