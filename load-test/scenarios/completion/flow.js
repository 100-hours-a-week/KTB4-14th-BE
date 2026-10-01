import { group } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

import { get, patch } from '../../lib/api.js';
import { checkApiResponse } from '../../lib/checks.js';
import { requireCompletionResetConfirmation, requireWriteConfirmation } from '../../lib/profile.js';
import { requireField, userForIteration } from '../../lib/test-data.js';

requireWriteConfirmation();
requireCompletionResetConfirmation();

// 이 값은 실행 중 실제로 고유 항목이 배정된 횟수다. summary의 앞 N개 사용자와 1:1 대응한다.
export const completionAttempts = new Counter('completion_attempts');

export function completionUserForCurrentIteration() {
    return userForIteration(exec.scenario.iterationInTest, { unique: true });
}

// LT-02 실제 사용자 흐름: 미완료 항목을 완료 처리한 뒤 일정을 다시 읽는다.
export function runCompletionFlow() {
    const user = completionUserForCurrentIteration();
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
