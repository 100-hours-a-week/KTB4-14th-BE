import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { runItineraryReadFlow } from './flow.js';

export const options = {
    scenarios: {
        itinerary_read: {
            executor: 'constant-arrival-rate',
            rate: arrivalRate('ITINERARY_FLOW_RATE_PER_MINUTE', 13),
            timeUnit: '1m',
            duration: duration('ITINERARY_READ_DURATION', '5m'),
            preAllocatedVUs: vus('ITINERARY_READ_PRE_ALLOCATED_VUS', 5),
            maxVUs: vus('ITINERARY_READ_MAX_VUS', 20),
        },
    },
};

export default runItineraryReadFlow;
