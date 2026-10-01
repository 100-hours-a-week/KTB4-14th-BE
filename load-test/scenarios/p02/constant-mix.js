import { runCompletionFlow } from '../completion/flow.js';
import { runItineraryReadFlow } from '../itinerary/flow.js';
import { runSseFlow } from '../sse/flow.js';

import { arrivalRate, duration, vus } from '../../lib/profile.js';

const profileDuration = duration('P02_DURATION', '10m');

// 기존 P-02 고정 혼합 프로파일: LT-01·LT-02·LT-06 흐름을 동시에 유지한다.
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

export function runItineraryRead() { runItineraryReadFlow(); }
export function runCompletion() { runCompletionFlow(); }
export function runSse() { runSseFlow(); }
