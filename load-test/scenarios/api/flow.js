import { group } from 'k6';
import { Counter } from 'k6/metrics';

import { get, patch } from '../../lib/api.js';
import { checkApiResponse } from '../../lib/checks.js';
import { requireWriteConfirmation } from '../../lib/profile.js';
import { currentUser, requireField } from '../../lib/test-data.js';

export const nicknameUpdateAttempts = new Counter('nickname_update_attempts');

function authenticatedUser({ unique = false } = {}) {
    const user = currentUser({ unique });
    return {
        token: requireField(user, 'accessToken'),
        travelPlanId: requireField(user, 'travelPlanId'),
    };
}

function runRead({ groupName, scenario, path, requestName }) {
    const { token } = authenticatedUser();

    group(groupName, () => {
        checkApiResponse(get(path, scenario, token, requestName), 200);
    });
}

// API-LT-01: 로그인 사용자의 전체 여행 목록을 직접 조회한다.
export function runMyTravelPlans() {
    runRead({
        groupName: 'API-LT-01 my travel plans',
        scenario: 'API-LT-01',
        path: '/api/travel-plans/me',
        requestName: 'my_travel_plans',
    });
}

// API-LT-02: 마이페이지 사용자 정보와 여행 집계 수를 직접 조회한다.
export function runUserMe() {
    runRead({
        groupName: 'API-LT-02 user me',
        scenario: 'API-LT-02',
        path: '/api/users/me',
        requestName: 'user_me',
    });
}

// API-LT-03: 사용자의 알림 목록 전체를 직접 조회한다.
export function runNotifications() {
    runRead({
        groupName: 'API-LT-03 notifications',
        scenario: 'API-LT-03',
        path: '/api/notifications',
        requestName: 'notifications',
    });
}

// API-LT-04: 사용자의 알림 설정을 직접 조회한다.
export function runNotificationSettings() {
    runRead({
        groupName: 'API-LT-04 notification settings',
        scenario: 'API-LT-04',
        path: '/api/notification-settings',
        requestName: 'notification_settings',
    });
}

// API-LT-05: 준비된 미래 여행의 생성 상태를 travelPlanId 기준으로 직접 조회한다.
export function runTravelPlanStatus() {
    const { token, travelPlanId } = authenticatedUser();

    group('API-LT-05 travel plan status', () => {
        checkApiResponse(
            get(`/api/travel-plans/${travelPlanId}/status`, 'API-LT-05', token, 'travel_plan_status'),
            200
        );
    });
}

function nextNickname() {
    // `u001`(4자)과 `u001t`(5자)를 VU별로 번갈아 사용한다.
    // 둘 다 Backend nickname validation(영문/숫자 2~5자)을 만족하고,
    // 같은 실행 안에서는 직전 요청과 다른 값이므로 매 iteration 변경 요청을 재현한다.
    const base = `u${String(__VU).padStart(3, '0')}`;
    return __ITER % 2 === 0 ? base : `${base}t`;
}

// API-LT-06: VU별 전용 사용자의 닉네임을 u001 ↔ u001t처럼 매 iteration마다 토글한다.
// 반복 실행을 위한 원복은 필요 없지만, Staging 전용 loadtest 계정에만 사용해야 한다.
export function runNicknameUpdate() {
    requireWriteConfirmation();

    const { token } = authenticatedUser({ unique: true });
    const nickname = nextNickname();

    group('API-LT-06 nickname update', () => {
        nicknameUpdateAttempts.add(1);
        checkApiResponse(
            patch('/api/users/me/nickname', { nickname }, 'API-LT-06', token, 'nickname_update'),
            200
        );
    });
}
