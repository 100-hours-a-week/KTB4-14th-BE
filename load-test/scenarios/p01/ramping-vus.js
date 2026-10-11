import { duration, vus } from '../../lib/profile.js';
import { currentUser, testUserCount } from '../../lib/test-data.js';
import { runP01GenerationFlow } from './flow.js';

const stage1Vus = vus('P01_RAMP_VU_STAGE_1', 1);
const stage2Vus = vus('P01_RAMP_VU_STAGE_2', 3);
const peakVus = vus('P01_RAMP_VU_PEAK', 10);
const configuredPeak = Math.max(stage1Vus, stage2Vus, peakVus);
if (configuredPeak > testUserCount()) {
    throw new Error(`P01 ramp peak (${configuredPeak}) exceeds configured test users (${testUserCount()})`);
}

export const options = {
    scenarios: {
        p01_full_generation_ramping_vus: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { target: stage1Vus, duration: duration('P01_RAMP_VU_STAGE_1_DURATION', '5m') },
                { target: stage2Vus, duration: duration('P01_RAMP_VU_STAGE_2_DURATION', '5m') },
                { target: peakVus, duration: duration('P01_RAMP_VU_UP_DURATION', '5m') },
                { target: peakVus, duration: duration('P01_RAMP_VU_PEAK_DURATION', '10m') },
                { target: 0, duration: duration('P01_RAMP_VU_DOWN_DURATION', '5m') },
            ],
            gracefulRampDown: duration('P01_RAMP_VU_GRACEFUL_RAMP_DOWN', '5m'),
            gracefulStop: duration('P01_RAMP_VU_GRACEFUL_STOP', '5m'),
        },
    },
};

// ramping-vus는 default function을 반복 호출한다. VU마다 한 사용자만 고정 배정하고,
// 한 번의 생성 전체 흐름이 끝난 뒤에만 다음 iteration을 시작한다.
export default function () {
    runP01GenerationFlow(currentUser({ unique: true }));
}
