import { group } from 'k6';

import { get } from '../lib/api.js';
import { checkApiResponse } from '../lib/checks.js';
import { arrivalRate, duration, vus } from '../lib/profile.js';
import { currentUser, requireField } from '../lib/test-data.js';

const rate = arrivalRate('ITINERARY_FLOW_RATE_PER_MINUTE', 13);

export const options = {
    scenarios: {
        itinerary_read: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1m',
            duration: duration('ITINERARY_READ_DURATION', '5m'),
            preAllocatedVUs: vus('ITINERARY_READ_PRE_ALLOCATED_VUS', 5),
            maxVUs: vus('ITINERARY_READ_MAX_VUS', 20),
        },
    },
};

// LT-01: 홈 진입(2개 조회) 후 일정 통합 조회. rate는 사용자 흐름 수이며,
// 이 흐름 하나는 Backend HTTP 요청 세 번을 생성한다.
export default function () {
    const user = currentUser();
    const token = requireField(user, 'accessToken');
    const travelPlanId = requireField(user, 'travelPlanId');

    group('LT-01 itinerary read', () => {
        checkApiResponse(get('/api/travel-plans/upcoming', 'LT-01', token, 'upcoming'), 200);
        checkApiResponse(get('/api/travel-plans/recent', 'LT-01', token, 'recent'), 200);
        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-01', token, 'itinerary'),
            200
        );
    });
}
