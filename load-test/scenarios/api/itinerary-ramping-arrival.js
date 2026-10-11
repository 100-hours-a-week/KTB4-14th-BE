import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { measurementThresholds } from '../../lib/single-api.js';
import { requireField } from '../../lib/test-data.js';
import { runSingleRead } from './single-flow.js';

const ramp = rampingArrivalScenario({ name: 'api_itinerary_ramp', prefix: 'ITINERARY_API_RAMP' });
export const options = { ...ramp.options, thresholds: measurementThresholds() };
export default function () {
    runSingleRead((user) => `/api/travel-plans/${requireField(user, 'travelPlanId')}/itinerary`, 'itinerary');
}
