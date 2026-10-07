import { group } from 'k6';

import { get } from '../../lib/api.js';
import { checkApiResponse } from '../../lib/checks.js';
import { currentUser, requireField } from '../../lib/test-data.js';

// LT-01: 홈 진입(2개 조회) 후 일정 통합 조회. 한 flow는 HTTP 요청 3개다.
export function runItineraryReadFlow() {
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
