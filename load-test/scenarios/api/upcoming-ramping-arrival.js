import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { measurementThresholds } from '../../lib/single-api.js';
import { runSingleRead } from './single-flow.js';

const ramp = rampingArrivalScenario({ name: 'api_upcoming_ramp', prefix: 'UPCOMING_RAMP' });
export const options = { ...ramp.options, thresholds: measurementThresholds() };
export default function () {
    runSingleRead('/api/travel-plans/upcoming', 'upcoming');
}
