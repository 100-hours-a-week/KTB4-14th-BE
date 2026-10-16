import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { get, post } from '../../lib/api.js';
import { checkApiResponse, responseData } from '../../lib/checks.js';
import { config } from '../../lib/config.js';
import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { duration, requireWriteConfirmation, requireAiMockConfirmation } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';
import { iterationBudget, uniqueUsers, artifact, metricValues, summaryText, measurementParams, preparationParams, measurementThresholds } from '../../lib/single-api.js';

requireWriteConfirmation();
requireAiMockConfirmation();
const request = JSON.parse(open(__ENV.CREATE_REQUEST_FILE || '../../data/create-request.json'));
const ramp = rampingArrivalScenario({
    name: 'api_travel_plan_create_ramp', prefix: 'CREATE_API_RAMP',
    defaults: { startRate: 1, stage1Rate: 3, stage2Rate: 5, peakRate: 10,
        stage1Duration: '10s', stage2Duration: '10s', upDuration: '10s', peakDuration: '10s', downDuration: '10s',
        preAllocatedVUs: 10, maxVUs: 100 },
});
const scenario = Object.values(ramp.options.scenarios)[0];
const budget = iterationBudget(scenario);
if (budget > testUserCount()) throw new Error(`Creation Ramp requires ${budget} unique users including scheduling margin, but only ${testUserCount()} exist`);
const users = uniqueUsers(budget);
const records = users.map((user) => ({
    attempts: new Counter(`creation_user_${user.userId}_attempts`),
    travel: new Trend(`creation_user_${user.userId}_travel_plan_id`),
    job: new Trend(`creation_user_${user.userId}_generation_job_id`),
}));
const attempts = new Counter('creation_api_attempts');
const accepted = new Counter('creation_api_accepted');
export const options = { ...ramp.options, setupTimeout: duration('CREATE_API_SETUP_TIMEOUT', '5m'), thresholds: measurementThresholds() };

export function setup() {
    for (const user of users) {
        const response = get('/api/travel-plans/me', 'API-CREATION-PREPARE', user.accessToken, 'creation_preflight_travels', preparationParams);
        if (!checkApiResponse(response, 200)) throw new Error(`Cannot prepare userId ${user.userId}: HTTP ${response.status}`);
        const travels = responseData(response);
        if (!Array.isArray(travels)) throw new Error('Creation preflight requires a travel list');
        if (travels.some((travel) => travel.status === 'GENERATING')) throw new Error(`userId ${user.userId} already has a generating travel; wait for completion before retrying`);
    }
}

export default function () {
    const index = exec.scenario.iterationInTest;
    if (index >= users.length) exec.test.abort('Creation test data exhausted; no user will be reused');
    const user = users[index];
    attempts.add(1);
    records[index].attempts.add(1);
    const response = post('/api/travel-plans', request, 'API-CREATION', user.accessToken, 'travel_plan_create', measurementParams);
    const valid = checkApiResponse(response, 202);
    const result = responseData(response);
    const idsValid = check(result, { 'creation IDs recorded': (data) => valid && Number.isSafeInteger(data?.travel_plan_id) && data.travel_plan_id > 0 && Number.isSafeInteger(data?.generation_job_id) && data.generation_job_id > 0 });
    if (idsValid) {
        accepted.add(1);
        records[index].travel.add(result.travel_plan_id);
        records[index].job.add(result.generation_job_id);
    }
}

export function handleSummary(data) {
    const results = users.flatMap((user) => {
        if (!(metricValues(data, `creation_user_${user.userId}_attempts`).count > 0)) return [];
        return [{ userId: user.userId,
            travelPlanId: metricValues(data, `creation_user_${user.userId}_travel_plan_id`).max ?? null,
            generationJobId: metricValues(data, `creation_user_${user.userId}_generation_job_id`).max ?? null }];
    });
    return { ...artifact('generation-results.json', { runId: config.testRunId, results }), stdout: summaryText(data) };
}
