import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

import { duration, requireAiMockConfirmation, requireWriteConfirmation, vus } from '../../lib/profile.js';
import { testUserCount, userForIteration } from '../../lib/test-data.js';
import { waitForNotification } from '../sse/flow.js';
import { runP01GenerationFlow } from './flow.js';

// P-01 생성 완료가 같은 사용자의 SSE notification 이벤트로 전달되는지 측정한다.
// 생성 성능 비교용 기존 P-01 executor들과 섞지 않고, 사용자당 한 번의 짝지어진 흐름만 수행한다.
const users = vus('P01_NOTIFICATION_USERS', 1);
const sseReadyDelay = duration('P01_NOTIFICATION_SSE_READY_DELAY', '5s');
const waitTimeout = duration('P01_NOTIFICATION_WAIT_TIMEOUT', '6m');
const sseMaxDuration = duration('P01_NOTIFICATION_SSE_MAX_DURATION', '7m');
const generationMaxDuration = duration('P01_NOTIFICATION_GENERATION_MAX_DURATION', '6m');

if (users > testUserCount()) {
    throw new Error(`P-01 notification users (${users}) exceeds configured test users (${testUserCount()})`);
}
if (durationMilliseconds(waitTimeout) >= durationMilliseconds(sseMaxDuration)) {
    throw new Error('P01_NOTIFICATION_SSE_MAX_DURATION must be longer than P01_NOTIFICATION_WAIT_TIMEOUT');
}

requireWriteConfirmation();
requireAiMockConfirmation();

export const notificationWaits = new Counter('p01_notification_waits');
export const notificationEventsReceived = new Counter('p01_notification_events_received');

export const options = {
    scenarios: {
        p01_notification_wait: {
            executor: 'per-vu-iterations',
            exec: 'waitForUserNotification',
            vus: users,
            iterations: 1,
            maxDuration: sseMaxDuration,
            gracefulStop: '0s',
        },
        p01_notification_generation: {
            executor: 'per-vu-iterations',
            exec: 'generateTravelForNotification',
            startTime: sseReadyDelay,
            vus: users,
            iterations: 1,
            maxDuration: generationMaxDuration,
            gracefulStop: '0s',
        },
    },
};

function userForCurrentScenarioIteration() {
    // 각 scenario의 iterationInTest는 0부터 독립적으로 증가한다.
    // 따라서 두 executor는 index가 같은 loadtest 사용자를 의도적으로 공유한다.
    return userForIteration(exec.scenario.iterationInTest, { unique: true });
}

export function waitForUserNotification() {
    notificationWaits.add(1);
    const result = waitForNotification(userForCurrentScenarioIteration(), waitTimeout);
    if (result.notificationReceived) {
        notificationEventsReceived.add(1);
    }
    check(result, {
        'P-01 notification event received': (value) => value.notificationReceived === true,
    });
}

export function generateTravelForNotification() {
    runP01GenerationFlow(userForCurrentScenarioIteration());
}

function durationMilliseconds(value) {
    const match = /^(\d+)(ms|s|m|h)$/.exec(value);
    const amount = Number(match[1]);
    return amount * ({ ms: 1, s: 1_000, m: 60_000, h: 3_600_000 }[match[2]]);
}
