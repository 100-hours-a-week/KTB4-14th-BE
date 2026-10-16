import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { currentUser, testUserCount } from '../../lib/test-data.js';
import { runP01GenerationFlow } from './flow.js';

const startRate = arrivalRate('P01_RAMP_START_RATE_PER_HOUR', 6);
const peakRate = arrivalRate('P01_RAMP_PEAK_RATE_PER_HOUR', 60);
const maxVUs = vus('P01_RAMP_MAX_VUS', 20);
if (maxVUs > testUserCount()) {
    throw new Error(`P01_RAMP_MAX_VUS (${maxVUs}) exceeds configured test users (${testUserCount()})`);
}

export const options = {
    scenarios: {
        p01_full_generation_ramp: {
            executor: 'ramping-arrival-rate',
            startRate,
            timeUnit: '1h',
            preAllocatedVUs: vus('P01_RAMP_PRE_ALLOCATED_VUS', 2),
            maxVUs,
            gracefulStop: duration('P01_RAMP_GRACEFUL_STOP', '5m'),
            stages: [
                { target: arrivalRate('P01_RAMP_STAGE_1_RATE_PER_HOUR', 12), duration: duration('P01_RAMP_STAGE_1_DURATION', '10m') },
                { target: arrivalRate('P01_RAMP_STAGE_2_RATE_PER_HOUR', 30), duration: duration('P01_RAMP_STAGE_2_DURATION', '10m') },
                { target: peakRate, duration: duration('P01_RAMP_UP_DURATION', '10m') },
                { target: peakRate, duration: duration('P01_RAMP_PEAK_DURATION', '20m') },
                { target: startRate, duration: duration('P01_RAMP_DOWN_DURATION', '10m') },
            ],
        },
    },
};

export default function () {
    // Ramp가 추가한 VU도 자신의 고정 사용자로 직렬 생성 흐름을 반복한다.
    runP01GenerationFlow(currentUser({ unique: true }));
}
