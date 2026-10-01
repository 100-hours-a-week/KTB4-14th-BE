import exec from 'k6/execution';
import { sleep } from 'k6';

import { duration, vus } from '../../lib/profile.js';
import { testUserCount, userForIteration } from '../../lib/test-data.js';
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

// ramping-vus는 default function을 반복 호출한다. 각 VU는 첫 호출에서만 생성 전체 흐름을
// 한 번 수행하고, 완료 뒤에는 idle 상태로 남는다. 따라서 같은 계정의 반복 생성·동시 생성이 없다.
let hasCreated = false;
export default function () {
    if (hasCreated) {
        sleep(Number(__ENV.P01_RAMP_VU_IDLE_SECONDS || 60));
        return;
    }
    hasCreated = true;
    runP01GenerationFlow(userForIteration(exec.scenario.iterationInTest, { unique: true }));
}
