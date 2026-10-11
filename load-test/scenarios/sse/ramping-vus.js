import { duration, vus } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';
import { runSseFlow } from './flow.js';

const first = vus('SSE_RAMP_STAGE_1_VUS', 1);
const second = vus('SSE_RAMP_STAGE_2_VUS', 10);
const third = vus('SSE_RAMP_STAGE_3_VUS', 30);
const peak = vus('SSE_RAMP_PEAK_VUS', 70);
const final = vus('SSE_RAMP_FINAL_VUS', 1);

for (const [name, value] of Object.entries({ first, second, third, peak, final })) {
    if (value > testUserCount()) {
        throw new Error(`SSE ramp ${name} VUs (${value}) exceeds configured test users (${testUserCount()})`);
    }
}

export const options = {
    scenarios: {
        sse_connections_ramp: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { target: first, duration: duration('SSE_RAMP_STAGE_1_DURATION', '30s') },
                { target: second, duration: duration('SSE_RAMP_STAGE_2_DURATION', '30s') },
                { target: third, duration: duration('SSE_RAMP_STAGE_3_DURATION', '1m') },
                { target: peak, duration: duration('SSE_RAMP_PEAK_DURATION', '2m') },
                { target: final, duration: duration('SSE_RAMP_DOWN_DURATION', '30s') },
            ],
            gracefulRampDown: duration('SSE_RAMP_GRACEFUL_RAMP_DOWN', '0s'),
            gracefulStop: '0s',
        },
    },
};

export default runSseFlow;
