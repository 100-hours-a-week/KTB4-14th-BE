import { get } from '../lib/api.js';
import { checkApiResponse } from '../lib/checks.js';
import { arrivalRate, duration, vus } from '../lib/profile.js';
import { currentUser, requireField } from '../lib/test-data.js';

export const options = {
    scenarios: {
        generation_polling: {
            executor: 'constant-arrival-rate',
            // P-01 예상 피크 0.494 RPS에 가까운 30 req/min을 기본값으로 둔다.
            rate: arrivalRate('GENERATION_POLL_RATE_PER_MINUTE', 30),
            timeUnit: '1m',
            duration: duration('GENERATION_POLL_DURATION', '5m'),
            preAllocatedVUs: vus('GENERATION_POLL_PRE_ALLOCATED_VUS', 5),
            maxVUs: vus('GENERATION_POLL_MAX_VUS', 20),
        },
    },
};

// LT-05: 생성 요청을 새로 만들지 않고, Staging에 준비된 Job을 반복 조회한다.
export default function () {
    const user = currentUser();
    const token = requireField(user, 'accessToken');
    const generationJobId = requireField(user, 'generationJobId');

    checkApiResponse(
        get(`/api/ai-generation-jobs/${generationJobId}`, 'LT-05', token, 'generation_status'),
        200
    );
}
