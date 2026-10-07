import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { runNotifications } from './flow.js';

export const options = rampingArrivalScenario({
    name: 'api_notifications_ramp',
    prefix: 'NOTIFICATIONS_RAMP',
}).options;

export default runNotifications;
