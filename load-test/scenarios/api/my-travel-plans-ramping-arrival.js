import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { runMyTravelPlans } from './flow.js';

export const options = rampingArrivalScenario({
    name: 'api_my_travel_plans_ramp',
    prefix: 'MY_TRAVELS_RAMP',
}).options;

export default runMyTravelPlans;
