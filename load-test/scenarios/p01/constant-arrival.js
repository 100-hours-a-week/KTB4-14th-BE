import exec from 'k6/execution';

import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { userForIteration } from '../../lib/test-data.js';
import { runP01GenerationFlow } from './flow.js';

export const options = {
    scenarios: {
        p01_full_generation: {
            executor: 'constant-arrival-rate',
            // 초기 피크 G=5.65건/시간을 반올림한 값이다.
            rate: arrivalRate('P01_CREATION_RATE_PER_HOUR', 6),
            timeUnit: '1h',
            duration: duration('P01_DURATION', '1h'),
            preAllocatedVUs: vus('P01_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('P01_MAX_VUS', 20),
            gracefulStop: duration('P01_GRACEFUL_STOP', '5m'),
        },
    },
};

export default function () {
    // 도착률 실행 하나마다 서로 다른 계정을 써서 동시 생성·데이터 매핑 충돌을 막는다.
    runP01GenerationFlow(userForIteration(exec.scenario.iterationInTest, { unique: true }));
}
