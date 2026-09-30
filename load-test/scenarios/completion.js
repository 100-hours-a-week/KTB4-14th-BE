import { group } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

import { get, patch } from '../lib/api.js';
import { checkApiResponse } from '../lib/checks.js';
import { arrivalRate, duration, requireWriteConfirmation, vus } from '../lib/profile.js';
import { requireField, userForIteration } from '../lib/test-data.js';

requireWriteConfirmation();

const completionAttempts = new Counter('completion_attempts');

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

// LT-02: VU 재사용 여부와 관계없이 각 iteration에 서로 다른 계정/항목을 배정한다.
export default function () {
    const user = userForIteration(exec.scenario.iterationInTest, { unique: true });
    const token = requireField(user, 'accessToken');
    const itemId = requireField(user, 'itineraryItemId');
    const travelPlanId = requireField(user, 'travelPlanId');

    completionAttempts.add(1);
    group('LT-02 completion and reread', () => {
        checkApiResponse(
            patch(`/api/itinerary-items/${itemId}/completion`, { is_completed: true }, 'LT-02', token, 'completion'),
            200
        );
        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-02', token, 'itinerary_after_completion'),
            200
        );
    });
}
