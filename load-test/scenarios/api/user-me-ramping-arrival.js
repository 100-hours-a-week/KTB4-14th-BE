import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { runUserMe } from './flow.js';

export const options = rampingArrivalScenario({
    name: 'api_user_me_ramp',
    prefix: 'USER_ME_RAMP',
}).options;

export default runUserMe;
