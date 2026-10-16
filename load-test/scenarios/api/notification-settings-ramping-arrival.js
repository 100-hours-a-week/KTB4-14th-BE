import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { runNotificationSettings } from './flow.js';

export const options = rampingArrivalScenario({
    name: 'api_notification_settings_ramp',
    prefix: 'NOTIFICATION_SETTINGS_RAMP',
}).options;

export default runNotificationSettings;
