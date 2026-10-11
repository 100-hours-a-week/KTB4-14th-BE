import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { measurementThresholds } from '../../lib/single-api.js';
import { runSingleRead } from './single-flow.js';

const ramp = rampingArrivalScenario({ name: 'api_recent_ramp', prefix: 'RECENT_RAMP' });
export const options = { ...ramp.options, thresholds: measurementThresholds() };
export default function () {
    runSingleRead('/api/travel-plans/recent', 'recent');
}
