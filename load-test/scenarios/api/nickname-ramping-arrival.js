import { rampingArrivalScenario } from '../../lib/ramping-arrival.js';
import { requireWriteConfirmation } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';
import { runNicknameUpdate } from './flow.js';

requireWriteConfirmation();

const scenario = rampingArrivalScenario({
    name: 'api_nickname_update_ramp',
    prefix: 'NICKNAME_RAMP',
    defaults: {
        maxVUs: 150,
    },
});

if (scenario.maxVUs > testUserCount()) {
    throw new Error(
        `NICKNAME_RAMP_MAX_VUS (${scenario.maxVUs}) exceeds configured test users (${testUserCount()})`
    );
}

export const options = scenario.options;
export default runNicknameUpdate;
