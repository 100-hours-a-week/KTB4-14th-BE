import { group, sleep } from 'k6';

import { get, patch, post } from '../lib/api.js';
import { checkApiResponse, responseData } from '../lib/checks.js';
import { requireCompletionResetConfirmation, requireExternalSmokeConfirmation, requireWriteConfirmation } from '../lib/profile.js';
import { currentUser, requireField } from '../lib/test-data.js';

const runCompletionSmoke = __ENV.RUN_COMPLETION_SMOKE === 'true';
const runCreationSmoke = __ENV.RUN_CREATION_SMOKE === 'true';

// open()은 k6 init context에서만 호출할 수 있다. 생성 Smoke를 명시한 경우에만
// 실제 요청 body 파일을 읽으므로, 일반 읽기 Smoke에는 외부 API 호출이 없다.
const creationRequest = runCreationSmoke
    ? JSON.parse(open(__ENV.CREATE_REQUEST_FILE || '../data/create-request.json'))
    : null;

if (runCompletionSmoke) {
    requireWriteConfirmation();
    requireCompletionResetConfirmation();
}

if (runCreationSmoke) {
    requireWriteConfirmation();
    requireExternalSmokeConfirmation();
}

export const options = {
    vus: 1,
    iterations: 1,
};

function runCreationFlow(token) {
    const created = post('/api/travel-plans', creationRequest, 'LT-04', token, 'travel_plan_create');
    checkApiResponse(created, 202);

    const data = responseData(created);
    if (!data || !data.travel_plan_id || !data.generation_job_id) {
        throw new Error('LT-04 create response is missing travel_plan_id or generation_job_id');
    }

    const attempts = Number(__ENV.CREATION_POLL_ATTEMPTS || 3);
    for (let attempt = 0; attempt < attempts; attempt += 1) {
        const status = get(
            `/api/ai-generation-jobs/${data.generation_job_id}`,
            'LT-04',
            token,
            'generation_status'
        );
        checkApiResponse(status, 200);

        const statusData = responseData(status);
        if (statusData && statusData.status === 'COMPLETED') {
            checkApiResponse(
                get(
                    `/api/travel-plans/${data.travel_plan_id}/itinerary`,
                    'LT-04',
                    token,
                    'itinerary_after_generation'
                ),
                200
            );
            return;
        }
        sleep(1);
    }
}

// LT-01·LT-02·LT-05의 안전한 단일 요청 검증과, 명시적으로 켠 경우 LT-04를 수행한다.
export default function () {
    const user = currentUser({ unique: runCompletionSmoke });
    const token = requireField(user, 'accessToken');
    const travelPlanId = requireField(user, 'travelPlanId');
    const generationJobId = requireField(user, 'generationJobId');

    group('LT smoke reads', () => {
        checkApiResponse(get('/api/regions', 'SMOKE', token, 'regions'), 200);
        checkApiResponse(get('/api/travel-plans/upcoming', 'SMOKE', token, 'upcoming'), 200);
        checkApiResponse(get('/api/travel-plans/recent', 'SMOKE', token, 'recent'), 200);
        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/itinerary`, 'SMOKE', token, 'itinerary'),
            200
        );
        checkApiResponse(
            get(`/api/ai-generation-jobs/${generationJobId}`, 'SMOKE', token, 'generation_status'),
            200
        );
    });

    if (runCompletionSmoke) {
        const itemId = requireField(user, 'itineraryItemId');
        group('LT-02 smoke completion', () => {
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

    if (runCreationSmoke) {
        group('LT-04 creation smoke', () => runCreationFlow(token));
    }
}
