import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

import { get, post } from '../../lib/api.js';
import { checkApiResponse, responseData } from '../../lib/checks.js';
import { requireAiMockConfirmation, requireWriteConfirmation } from '../../lib/profile.js';
import { requireField } from '../../lib/test-data.js';

const creationRequest = JSON.parse(open(__ENV.CREATE_REQUEST_FILE || '../../data/create-request.json'));
const pollIntervalSeconds = positiveInteger('P01_POLL_INTERVAL_SECONDS', 1);
const pollTimeoutSeconds = positiveInteger('P01_POLL_TIMEOUT_SECONDS', 300);

requireWriteConfirmation();
requireAiMockConfirmation();

export const creationAttempts = new Counter('p01_creation_attempts');
export const generationCompleted = new Counter('p01_generation_completed');
export const generationFailed = new Counter('p01_generation_failed');
export const generationPollRequests = new Counter('p01_generation_poll_requests');

function positiveInteger(name, fallback) {
    const value = Number(__ENV[name] || fallback);
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

// LT-04/P-01: 지역 조회 → 생성 접수 → Job 폴링 → 생성된 일정 조회.
export function runP01GenerationFlow(user) {
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
        const statusResponse = get(`/api/ai-generation-jobs/${generationJobId}`, 'LT-04', token, 'generation_status');
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
