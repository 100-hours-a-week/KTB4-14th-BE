import { arrivalRate, duration, vus } from '../../lib/profile.js';
import { runItineraryReadFlow } from './flow.js';

const startRate = arrivalRate('ITINERARY_RAMP_START_RATE_PER_MINUTE', 13);
const peakRate = arrivalRate('ITINERARY_RAMP_PEAK_RATE_PER_MINUTE', 1000);

export const options = {
    scenarios: {
        itinerary_read_ramp: {
            executor: 'ramping-arrival-rate',
            startRate,
            timeUnit: '1m',
            preAllocatedVUs: vus('ITINERARY_RAMP_PRE_ALLOCATED_VUS', 10),
            maxVUs: vus('ITINERARY_RAMP_MAX_VUS', 200),
            stages: [
                { target: arrivalRate('ITINERARY_RAMP_STAGE_1_RATE_PER_MINUTE', 200), duration: duration('ITINERARY_RAMP_STAGE_1_DURATION', '1m') },
                { target: arrivalRate('ITINERARY_RAMP_STAGE_2_RATE_PER_MINUTE', 500), duration: duration('ITINERARY_RAMP_STAGE_2_DURATION', '1m') },
                { target: peakRate, duration: duration('ITINERARY_RAMP_UP_DURATION', '1m') },
                { target: peakRate, duration: duration('ITINERARY_RAMP_PEAK_DURATION', '5m') },
                { target: startRate, duration: duration('ITINERARY_RAMP_DOWN_DURATION', '1m') },
            ],
        },
    },
};

export default runItineraryReadFlow;
