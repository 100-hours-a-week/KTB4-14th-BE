import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { get, patch } from '../../lib/api.js';
import { checkApiResponse, responseData } from '../../lib/checks.js';
import { config } from '../../lib/config.js';
import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { duration, requireWriteConfirmation, requireCompletionResetConfirmation } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';
import { uniqueUsers, artifact, metricValues, summaryText, measurementParams, preparationParams, measurementThresholds } from '../../lib/single-api.js';

requireWriteConfirmation();
requireCompletionResetConfirmation();
const ramp = rampingArrivalScenario({ name: 'api_completion_ramp', prefix: 'COMPLETION_API_RAMP' });
if (ramp.maxVUs > testUserCount()) throw new Error('COMPLETION_API_RAMP_MAX_VUS exceeds test users');
const users = uniqueUsers(ramp.maxVUs, ['itineraryItemId']);
const itemAttempts = users.map((user) => new Counter(`completion_item_${user.itineraryItemId}_attempts`));
const attempts = new Counter('completion_api_attempts');
const falseToTrue = new Counter('completion_false_to_true');
const trueToFalse = new Counter('completion_true_to_false');
const reconfirmed = new Counter('completion_state_reconfirmed');
const uncertain = new Counter('completion_state_uncertain');
let completed;
let stateKnown = true;
export const options = { ...ramp.options, setupTimeout: duration('COMPLETION_API_SETUP_TIMEOUT', '5m'), thresholds: measurementThresholds() };

export function setup() {
    return users.map((user) => {
        const response = get(`/api/travel-plans/${user.travelPlanId}/itinerary`, 'API-COMPLETION-PREPARE', user.accessToken, 'completion_initial_state', preparationParams);
        if (!checkApiResponse(response, 200)) throw new Error(`Cannot prepare userId ${user.userId}: HTTP ${response.status}`);
        const items = responseData(response)?.days?.flatMap((day) => day.items || []) || [];
        const item = items.find((entry) => entry.itinerary_item_id === user.itineraryItemId);
        if (typeof item?.is_completed !== 'boolean') throw new Error(`Missing completion state for item ${user.itineraryItemId}`);
        return item.is_completed;
    });
}

export default function (initialStates) {
    const index = __VU - 1;
    const user = users[index];
    if (!user) throw new Error('Completion VU has no dedicated test item');
    if (completed === undefined) completed = initialStates[index];
    const target = !completed;
    attempts.add(1);
    itemAttempts[index].add(1); // 응답 전에 기록: Timeout·중단 항목도 복구 대상이다.
    const response = patch(`/api/itinerary-items/${user.itineraryItemId}/completion`, { is_completed: target }, 'API-COMPLETION', user.accessToken, 'completion_toggle', measurementParams);
    const validResponse = checkApiResponse(response, 200);
    const result = responseData(response);
    const confirmed = check(result, { 'completion target confirmed': (data) => validResponse && data?.itinerary_item_id === user.itineraryItemId && data?.is_completed === target });
    if (!confirmed) {
        stateKnown = false;
        uncertain.add(1);
        return; // 다음 요청도 같은 목표값으로 재동기화한다. 측정 중 GET은 하지 않는다.
    }
    if (stateKnown) {
        if (target) falseToTrue.add(1);
        else trueToFalse.add(1);
    } else reconfirmed.add(1);
    completed = target;
    stateKnown = true;
}

export function handleSummary(data) {
    const items = users.filter((user) => metricValues(data, `completion_item_${user.itineraryItemId}_attempts`).count > 0)
        .map(({ userId, itineraryItemId }) => ({ userId, itineraryItemId }));
    return { ...artifact('completion-items.json', { runId: config.testRunId, items }), stdout: summaryText(data) };
}
