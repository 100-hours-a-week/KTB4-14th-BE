import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';
import sse from 'k6/x/sse';

import { params } from '../../lib/api.js';
import { checkStatus } from '../../lib/checks.js';
import { config } from '../../lib/config.js';
import { requireField, userForIteration } from '../../lib/test-data.js';

export const connectionAttempts = new Counter('sse_connection_attempts');
export const connectionOpened = new Counter('sse_connection_opened');
export const connectedEvents = new Counter('sse_connected_events');
export const eventsReceived = new Counter('sse_events_received');
export const connectionErrors = new Counter('sse_connection_errors');
export const handshake200 = new Counter('sse_handshake_200');

// SSE용 사용자 배정은 전체 k6 __VU가 아니라 이 executor의 iteration index로 한다.
// 따라서 P-02의 다른 executor가 VU를 많이 사용해도 SSE 계정 배정이 흔들리지 않는다.
export function sseUserForCurrentIteration() {
    return userForIteration(exec.scenario.iterationInTest, { unique: true });
}

// 연결을 유지하는 시나리오는 callback이 반환되지 않도록 SSE 스트림을 계속 열어 둔다.
// ramp-down 또는 테스트 종료에서 k6가 해당 iteration을 중단하는 것은 정상 동작이다.
export function runSseFlow() {
    return subscribe({ closeAfterConnected: false }).response;
}

// handshake Smoke는 Backend가 구독 직후 보내는 `connected` 이벤트를 받은 뒤 연결을 닫는다.
// 이 경우 sse.open()이 HTTP 응답을 반환하므로 status 200을 명시적으로 검증할 수 있다.
export function runSseHandshakeSmoke() {
    return subscribe({ closeAfterConnected: true }).response;
}

// P-01 알림 전달 검증은 생성 요청 사용자와 같은 사용자로 SSE를 구독해야 한다.
// notification 이벤트를 받으면 연결을 닫아 per-vu-iterations가 정상 종료할 수 있게 한다.
export function waitForNotification(user, requestTimeout) {
    return subscribe({
        user,
        closeAfterConnected: false,
        closeAfterNotification: true,
        requestTimeout,
    });
}

function subscribe({
    user = sseUserForCurrentIteration(),
    closeAfterConnected,
    closeAfterNotification = false,
    requestTimeout,
}) {
    const token = requireField(user, 'accessToken');
    let notificationReceived = false;

    checkStatus(getUnreadCount(token), 200);
    connectionAttempts.add(1);
    // k6 summary는 값이 한 번도 기록되지 않은 Counter를 생략한다.
    // 성공한 실행에서도 오류 지표를 0으로 남겨 assert 단계가 일관되게 읽도록 한다.
    connectionErrors.add(0);

    const response = sse.open(
        `${config.baseUrl}/api/notifications/subscribe`,
        sseParams(token, requestTimeout),
        (client) => {
            client.on('open', () => {
                connectionOpened.add(1);
            });

            client.on('event', (event) => {
                eventsReceived.add(1);

                if (event.name === 'connected') {
                    connectedEvents.add(1);
                    if (closeAfterConnected) {
                        client.close();
                    }
                }

                if (event.name === 'notification') {
                    notificationReceived = true;
                    if (closeAfterNotification) {
                        client.close();
                    }
                }
            });

            client.on('error', () => {
                connectionErrors.add(1);
            });
        }
    );

    // Holding mode normally reaches here only when the server closes the stream.
    // Smoke mode closes deliberately after `connected`, so its HTTP 200 can be checked.
    if (!response || response.status === 0) {
        connectionErrors.add(1);
        return { response, notificationReceived };
    }

    if (closeAfterConnected) {
        if (response.status === 200) {
            handshake200.add(1);
        } else {
            connectionErrors.add(1);
        }
        checkStatus(response, 200);
        return { response, notificationReceived };
    }

    if (closeAfterNotification) {
        if (!notificationReceived) {
            connectionErrors.add(1);
        }
        checkStatus(response, 200);
        return { response, notificationReceived };
    }

    // 예상하지 않은 서버 종료 뒤 즉시 재구독하는 hot loop를 피한다.
    sleep(Number(__ENV.SSE_RECONNECT_DELAY_SECONDS || 1));
    return { response, notificationReceived };
}

function getUnreadCount(token) {
    return http.get(
        `${config.baseUrl}/api/notifications/unread-count`,
        params('LT-06', token, 'notification_unread_count')
    );
}

function sseParams(token, requestTimeout) {
    const requestParams = params('LT-06', token, 'sse_subscribe');
    return {
        ...requestParams,
        headers: {
            ...requestParams.headers,
            Accept: 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        timeout: requestTimeout || __ENV.SSE_REQUEST_TIMEOUT || '31m',
    };
}
