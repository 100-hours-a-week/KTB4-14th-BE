import exec from 'k6/execution';

import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { userForIteration } from '../../lib/test-data.js';
import { runP01GenerationFlow } from './flow.js';

const startRate = arrivalRate('P01_RAMP_START_RATE_PER_HOUR', 6);
const peakRate = arrivalRate('P01_RAMP_PEAK_RATE_PER_HOUR', 60);

export const options = {
    scenarios: {
        p01_full_generation_ramp: {
            executor: 'ramping-arrival-rate',
            startRate,
            timeUnit: '1h',
            preAllocatedVUs: vus('P01_RAMP_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('P01_RAMP_MAX_VUS', 20),
            gracefulStop: duration('P01_RAMP_GRACEFUL_STOP', '5m'),
            stages: [
                { target: arrivalRate('P01_RAMP_STAGE_1_RATE_PER_HOUR', 12), duration: duration('P01_RAMP_STAGE_1_DURATION', '10m') },
                { target: arrivalRate('P01_RAMP_STAGE_2_RATE_PER_HOUR', 30), duration: duration('P01_RAMP_STAGE_2_DURATION', '10m') },
                { target: peakRate, duration: duration('P01_RAMP_UP_DURATION', '10m') },
                { target: peakRate, duration: duration('P01_RAMP_PEAK_DURATION', '20m') },
                { target: startRate, duration: duration('P01_RAMP_DOWN_DURATION', '10m') },
            ],
        },
    },
};

export default function () {
    runP01GenerationFlow(userForIteration(exec.scenario.iterationInTest, { unique: true }));
}
