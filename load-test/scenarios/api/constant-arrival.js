import {
    runMyTravelPlans,
    runNicknameUpdate,
    runNotifications,
    runNotificationSettings,
    runTravelPlanStatus,
    runUserMe,
} from './flow.js';
import { arrivalRate, duration, requireWriteConfirmation, vus } from '../../lib/profile.js';
import { testUserCount } from '../../lib/test-data.js';

// `run-rps.sh`가 API_TARGET을 지정해 단일 API를 고정 HTTP RPS로 실행한다.
// 기존 endpoint별 ramping-arrival 파일은 실제 증가 패턴 비교용으로 그대로 유지한다.
const targets = {
    'my-travels': runMyTravelPlans,
    'user-me': runUserMe,
    notifications: runNotifications,
    'notification-settings': runNotificationSettings,
    'travel-plan-status': runTravelPlanStatus,
    nickname: runNicknameUpdate,
};

const target = __ENV.API_TARGET;
const runTarget = targets[target];
if (!runTarget) {
    throw new Error(`API_TARGET must be one of: ${Object.keys(targets).join(', ')}`);
}

const maxVUs = vus('API_MAX_VUS', 100);
if (target === 'nickname') {
    requireWriteConfirmation();
    if (maxVUs > testUserCount()) {
        throw new Error(`API_MAX_VUS (${maxVUs}) exceeds configured test users (${testUserCount()}) for nickname`);
    }
}

export const options = {
    scenarios: {
        api_direct_rps: {
            executor: 'constant-arrival-rate',
            rate: arrivalRate('API_RPS'),
            timeUnit: '1s',
            duration: duration('API_DURATION', '5m'),
            preAllocatedVUs: vus('API_PRE_ALLOCATED_VUS', 10),
            maxVUs,
        },
    },
};

export default runTarget;
