import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { runCompletionFlow } from './flow.js';

export const options = {
    scenarios: {
        completion_and_reread: {
            executor: 'constant-arrival-rate',
            rate: arrivalRate('COMPLETION_FLOW_RATE_PER_MINUTE', 1),
            timeUnit: '1m',
            duration: duration('COMPLETION_DURATION', '5m'),
            preAllocatedVUs: vus('COMPLETION_PRE_ALLOCATED_VUS', 2),
            maxVUs: vus('COMPLETION_MAX_VUS', 20),
        },
    },
};

export default runCompletionFlow;
