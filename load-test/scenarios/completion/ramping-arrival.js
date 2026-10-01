import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { runCompletionFlow } from './flow.js';

const startRate = arrivalRate('COMPLETION_RAMP_START_RATE_PER_MINUTE', 1);
const peakRate = arrivalRate('COMPLETION_RAMP_PEAK_RATE_PER_MINUTE', 10);

export const options = {
    scenarios: {
        completion_and_reread_ramp: {
            executor: 'ramping-arrival-rate',
            startRate,
            timeUnit: '1m',
            preAllocatedVUs: vus('COMPLETION_RAMP_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('COMPLETION_RAMP_MAX_VUS', 20),
            stages: [
                { target: arrivalRate('COMPLETION_RAMP_STAGE_1_RATE_PER_MINUTE', 2), duration: duration('COMPLETION_RAMP_STAGE_1_DURATION', '1m') },
                { target: arrivalRate('COMPLETION_RAMP_STAGE_2_RATE_PER_MINUTE', 5), duration: duration('COMPLETION_RAMP_STAGE_2_DURATION', '1m') },
                { target: peakRate, duration: duration('COMPLETION_RAMP_UP_DURATION', '1m') },
                { target: peakRate, duration: duration('COMPLETION_RAMP_PEAK_DURATION', '5m') },
                { target: startRate, duration: duration('COMPLETION_RAMP_DOWN_DURATION', '1m') },
            ],
        },
    },
};

export default runCompletionFlow;
