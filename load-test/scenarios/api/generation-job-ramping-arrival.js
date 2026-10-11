import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { measurementThresholds } from '../../lib/single-api.js';
import { requireField } from '../../lib/test-data.js';
import { runSingleRead } from './single-flow.js';

const ramp = rampingArrivalScenario({ name: 'api_generation_job_ramp', prefix: 'GENERATION_JOB_RAMP' });
export const options = { ...ramp.options, thresholds: measurementThresholds() };
export default function () {
    runSingleRead((user) => `/api/ai-generation-jobs/${requireField(user, 'generationJobId')}`, 'generation_job');
}
