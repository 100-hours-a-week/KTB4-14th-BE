import runCompletionFlow from './completion.js';
import runItineraryReadFlow from './itinerary-read.js';
import runSseFlow from './sse.js';

import { arrivalRate, duration, vus } from '../lib/profile.js';

const profileDuration = duration('P02_DURATION', '10m');

// P-02는 기존 LT-01·LT-02·LT-06 요청 흐름을 바꾸지 않고 동시에 실행한다.
export const options = {
    scenarios: {
        p02_itinerary_read: {
            executor: 'constant-arrival-rate',
            exec: 'runItineraryRead',
            rate: arrivalRate('ITINERARY_FLOW_RATE_PER_MINUTE', 13),
            timeUnit: '1m',
            duration: profileDuration,
            preAllocatedVUs: vus('ITINERARY_READ_PRE_ALLOCATED_VUS', 5),
            maxVUs: vus('ITINERARY_READ_MAX_VUS', 20),
        },
        p02_completion: {
            executor: 'constant-arrival-rate',
            exec: 'runCompletion',
            rate: arrivalRate('COMPLETION_FLOW_RATE_PER_MINUTE', 5),
            timeUnit: '1m',
            duration: profileDuration,
            preAllocatedVUs: vus('COMPLETION_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('COMPLETION_MAX_VUS', 20),
        },
        p02_sse: {
            executor: 'constant-vus',
            exec: 'runSse',
            vus: vus('SSE_CONNECTIONS', 10),
            duration: profileDuration,
            gracefulStop: '0s',
        },
    },
};

export function runItineraryRead() {
    runItineraryReadFlow();
}

export function runCompletion() {
    runCompletionFlow();
}

export function runSse() {
    runSseFlow();
}
