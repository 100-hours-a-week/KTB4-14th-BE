import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { currentUser, testUserCount } from '../../lib/test-data.js';
import { runP01GenerationFlow } from './flow.js';

const maxVUs = vus('P01_MAX_VUS', 20);
if (maxVUs > testUserCount()) {
    throw new Error(`P01_MAX_VUS (${maxVUs}) exceeds configured test users (${testUserCount()})`);
}

export const options = {
    scenarios: {
        p01_full_generation: {
            executor: 'constant-arrival-rate',
            // 초기 피크 G=5.65건/시간을 반올림한 값이다.
            rate: arrivalRate('P01_CREATION_RATE_PER_HOUR', 6),
            timeUnit: '1h',
            duration: duration('P01_DURATION', '1h'),
            preAllocatedVUs: vus('P01_PRE_ALLOCATED_VUS', 2),
            maxVUs,
            gracefulStop: duration('P01_GRACEFUL_STOP', '5m'),
        },
    },
};

export default function () {
    // VU마다 한 사용자를 고정 배정한다. VU의 iteration은 직렬이므로 같은 사용자의
    // 다음 여행 생성은 이전 생성·폴링·일정 조회가 모두 끝난 뒤에만 시작된다.
    runP01GenerationFlow(currentUser({ unique: true }));
}
