import { check, group, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

import { get, post } from '../lib/api.js';
import { checkApiResponse, responseData } from '../lib/checks.js';
import { arrivalRate, duration, requireAiMockConfirmation, requireWriteConfirmation, vus } from '../lib/profile.js';
import { requireField, userForIteration } from '../lib/test-data.js';

const creationRequest = JSON.parse(open(__ENV.CREATE_REQUEST_FILE || '../data/create-request.json'));
const pollIntervalSeconds = positiveInteger('P01_POLL_INTERVAL_SECONDS', 1);
const pollTimeoutSeconds = positiveInteger('P01_POLL_TIMEOUT_SECONDS', 300);

requireWriteConfirmation();
requireAiMockConfirmation();

const creationAttempts = new Counter('p01_creation_attempts');
const generationCompleted = new Counter('p01_generation_completed');
const generationFailed = new Counter('p01_generation_failed');
const generationPollRequests = new Counter('p01_generation_poll_requests');

export const options = {
    scenarios: {
        p01_full_generation: {
            executor: 'constant-arrival-rate',
            // 초기 피크 G=5.65건/시간을 반올림한 값이다.
            rate: arrivalRate('P01_CREATION_RATE_PER_HOUR', 6),
            timeUnit: '1h',
            duration: duration('P01_DURATION', '1h'),
            preAllocatedVUs: vus('P01_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('P01_MAX_VUS', 20),
            gracefulStop: duration('P01_GRACEFUL_STOP', '5m'),
        },
    },
};

function positiveInteger(name, fallback) {
    const value = Number(__ENV[name] || fallback);
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

// LT-04: 지역 조회 → 생성 접수 → 생성 완료까지 상태 조회 → 생성된 일정 조회.
export default function () {
    // 실행 하나에서 동일 계정이 동시에 여러 여행을 생성하지 않도록 iteration별로 계정을 분리한다.
    const user = userForIteration(exec.scenario.iterationInTest, { unique: true });
    const token = requireField(user, 'accessToken');

    group('P-01 LT-04 full generation', () => {
        requireSuccess(get('/api/regions', 'LT-04', token, 'regions'), 200, 'region lookup');

        creationAttempts.add(1);
        const created = post('/api/travel-plans', creationRequest, 'LT-04', token, 'travel_plan_create');
        requireSuccess(created, 202, 'travel plan creation');

        const createdData = responseData(created);
        const travelPlanId = createdData?.travel_plan_id;
        const generationJobId = createdData?.generation_job_id;
        if (!Number.isSafeInteger(travelPlanId) || !Number.isSafeInteger(generationJobId)) {
            throw new Error('LT-04 create response is missing numeric travel_plan_id or generation_job_id');
        }

        waitForGeneration(token, generationJobId);
        requireSuccess(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'LT-04', token, 'itinerary_after_generation'),
            200,
            'generated itinerary lookup'
        );
    });
}

function waitForGeneration(token, generationJobId) {
    const deadline = Date.now() + pollTimeoutSeconds * 1_000;
    while (Date.now() < deadline) {
        const statusResponse = get(
            `/api/ai-generation-jobs/${generationJobId}`,
            'LT-04',
            token,
            'generation_status'
        );
        generationPollRequests.add(1);
        requireSuccess(statusResponse, 200, 'generation status lookup');

        const status = responseData(statusResponse);
        if (status?.status === 'COMPLETED') {
            generationCompleted.add(1);
            check(status, { 'generation job completed': (data) => data.status === 'COMPLETED' });
            return;
        }
        if (status?.status === 'FAILED') {
            generationFailed.add(1);
            check(status, { 'generation job completed': () => false });
            throw new Error(`Generation job ${generationJobId} failed: ${status.error_message || 'no error message'}`);
        }
        sleep(pollIntervalSeconds);
    }

    generationFailed.add(1);
    check({}, { 'generation job completed': () => false });
    throw new Error(`Generation job ${generationJobId} did not complete within ${pollTimeoutSeconds}s`);
}

function requireSuccess(response, expectedStatus, operation) {
    if (!checkApiResponse(response, expectedStatus)) {
        throw new Error(`${operation} returned HTTP ${response.status}`);
    }
}
