import { group } from 'k6';
import { Counter } from 'k6/metrics';

import { get, patch } from '../../lib/api.js';
import { checkApiResponse, responseData } from '../../lib/checks.js';
import { duration, vus } from '../../lib/profile.js';
import { requireField, testUserCount } from '../../lib/test-data.js';
import { completionUserForCurrentIteration } from './flow.js';

export const completionToggleAttempts = new Counter('completion_toggle_attempts');
export const completionFalseToTrue = new Counter('completion_false_to_true');
export const completionTrueToFalse = new Counter('completion_true_to_false');

const toggleVus = vus('COMPLETION_TOGGLE_VUS', 1);
const toggleIterations = vus('COMPLETION_TOGGLE_ITERATIONS', 1);
if (toggleVus * toggleIterations > testUserCount()) {
    throw new Error(`Completion Toggle needs ${toggleVus * toggleIterations} unique items, but only ${testUserCount()} are configured`);
}

export const options = {
    scenarios: {
        completion_toggle: {
            executor: 'per-vu-iterations',
            vus: toggleVus,
            iterations: toggleIterations,
            maxDuration: duration('COMPLETION_TOGGLE_MAX_DURATION', '5m'),
        },
    },
};

// 반복 실행 편의·양방향 API 검증용. 한 실행에서 각 고유 항목은 정확히 한 번만 사용한다.
export default function () {
    const user = completionUserForCurrentIteration();
    const token = requireField(user, 'accessToken');
    const itemId = requireField(user, 'itineraryItemId');
    const travelPlanId = requireField(user, 'travelPlanId');

    completionToggleAttempts.add(1);
    group('LT-02 completion toggle', () => {
        const before = get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-02-TOGGLE', token, 'itinerary_before_toggle');
        if (!checkApiResponse(before, 200)) {
            throw new Error(`Itinerary lookup before toggle returned HTTP ${before.status}`);
        }
        const completed = completionState(responseData(before), itemId);
        if (completed === null) {
            throw new Error(`itineraryItemId ${itemId} was not found in travelPlanId ${travelPlanId}`);
        }

        const targetCompleted = !completed;
        const changed = patch(
            `/api/itinerary-items/${itemId}/completion`,
            { is_completed: targetCompleted },
            'LT-02-TOGGLE',
            token,
            'completion_toggle'
        );
        if (!checkApiResponse(changed, 200)) {
            throw new Error(`Completion toggle returned HTTP ${changed.status}`);
        }
        const result = responseData(changed);
        // 동일 상태 PATCH 성공은 전환 성공으로 세지 않는다. 응답이 실제 목표 상태를 확인해야 한다.
        if (result?.is_completed === targetCompleted) {
            if (completed) completionTrueToFalse.add(1);
            else completionFalseToTrue.add(1);
        }

        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-02-TOGGLE', token, 'itinerary_after_toggle'),
            200
        );
    });
}

function completionState(itinerary, itemId) {
    if (!Array.isArray(itinerary?.days)) return null;
    for (const day of itinerary.days) {
        for (const item of day?.items || []) {
            if (item?.itinerary_item_id === itemId) return item.is_completed;
        }
    }
    return null;
}
