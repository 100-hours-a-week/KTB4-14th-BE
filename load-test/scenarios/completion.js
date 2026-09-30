import { group } from 'k6';

import { get, patch } from '../lib/api.js';
import { checkApiResponse } from '../lib/checks.js';
import { arrivalRate, duration, requireWriteConfirmation, vus } from '../lib/profile.js';
import { currentUser, requireField } from '../lib/test-data.js';

requireWriteConfirmation();

export const options = {
    scenarios: {
        completion_and_reread: {
            executor: 'constant-arrival-rate',
            rate: arrivalRate('COMPLETION_FLOW_RATE_PER_MINUTE', 1),
            timeUnit: '1m',
            duration: duration('COMPLETION_DURATION', '5m'),
            preAllocatedVUs: vus('COMPLETION_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('COMPLETION_MAX_VUS', 20),
        },
    },
};

// LT-02: 동일 일정 항목을 동시에 갱신하지 않도록 VU마다 전용 계정/항목을 쓴다.
export default function () {
    const user = currentUser({ unique: true });
    const token = requireField(user, 'accessToken');
    const itemId = requireField(user, 'itineraryItemId');
    const travelPlanId = requireField(user, 'travelPlanId');

    group('LT-02 completion and reread', () => {
        checkApiResponse(
            patch(`/api/itinerary-items/${itemId}/completion`, { isCompleted: true }, 'LT-02', token, 'completion'),
            200
        );
        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-02', token, 'itinerary_after_completion'),
            200
        );
    });
}
