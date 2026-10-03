import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { duration } from '../../lib/profile.js';
import { currentUser, testUserCount } from '../../lib/test-data.js';
import { durationSeconds, uniqueUsers } from '../../lib/single-api.js';
import { runSingleSseSession } from '../sse/flow.js';

// RateはHTTP RPSではなく新規接続開始数/秒。
const holdSeconds = durationSeconds(duration('SSE_API_HOLD_DURATION', '10s'));
const ramp = rampingArrivalScenario({ name: 'api_sse_arrival_ramp', prefix: 'SSE_API_RAMP',
    defaults: { startRate: 1, stage1Rate: 2, stage2Rate: 3, peakRate: 5, preAllocatedVUs: 60, maxVUs: 100 } });
if (ramp.maxVUs > testUserCount()) throw new Error('SSE_API_RAMP_MAX_VUS exceeds test users');
uniqueUsers(ramp.maxVUs);
const scenario = Object.values(ramp.options.scenarios)[0];
scenario.gracefulStop = `${Math.ceil(holdSeconds + 5)}s`;
export const options = ramp.options;
export default function () {
    runSingleSseSession(currentUser({ unique: true }), holdSeconds);
}
