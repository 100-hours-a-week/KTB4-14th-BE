import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { runTravelPlanStatus } from './flow.js';

export const options = rampingArrivalScenario({
    name: 'api_travel_plan_status_ramp',
    prefix: 'TRAVEL_PLAN_STATUS_RAMP',
}).options;

export default runTravelPlanStatus;
