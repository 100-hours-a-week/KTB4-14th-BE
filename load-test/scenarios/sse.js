import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';

import { params } from '../lib/api.js';
import { checkStatus } from '../lib/checks.js';
import { duration, vus } from '../lib/profile.js';
import { config } from '../lib/config.js';
import { currentUser, requireField } from '../lib/test-data.js';

const connectionAttempts = new Counter('sse_connection_attempts');
const connectionResponses = new Counter('sse_connection_responses');

export const options = {
    scenarios: {
        sse_connections: {
            executor: 'constant-vus',
            vus: vus('SSE_CONNECTIONS', 1),
            duration: duration('SSE_DURATION', '1m'),
            gracefulStop: '0s',
        },
    },
};

// LT-06: k6 기본 HTTP 모듈은 열린 SSE 스트림의 이벤트를 읽는 클라이언트가 아니다.
// 각 VU는 unread-count 확인 후 하나의 스트림을 유지하며, 연결 수와 서버 로그를
// 같은 시간대에 관측한다. duration 종료가 연결 종료를 유도하므로 재실행 시 재연결을
// 검증할 수 있다.
export default function () {
    const user = currentUser({ unique: true });
    const token = requireField(user, 'accessToken');

    checkStatus(getUnreadCount(token), 200);
    connectionAttempts.add(1);

    const response = http.get(
        `${config.baseUrl}/api/notifications/subscribe`,
        params('LT-06', token, 'sse_subscribe', {
            headers: {
                ...params('LT-06', token, 'sse_subscribe').headers,
                Accept: 'text/event-stream',
                'Cache-Control': 'no-cache',
            },
            // 서버 기본 emitter timeout(30분)보다 충분히 크게 둔다. 시나리오 duration이
            // 먼저 끝나 k6가 연결을 종료하고, 다음 실행이 재연결을 검증한다.
            timeout: __ENV.SSE_REQUEST_TIMEOUT || '31m',
        })
    );

    // 시나리오 종료 시 k6가 스트림을 중단하면 응답 객체가 없을 수 있다. 정상적으로
    // 서버가 연결을 끝낸 경우에만 200 handshake를 검증한다.
    if (response && response.status !== 0) {
        connectionResponses.add(1);
        checkStatus(response, 200);
        sleep(Number(__ENV.SSE_RECONNECT_DELAY_SECONDS || 1));
    }
}

function getUnreadCount(token) {
    return http.get(
        `${config.baseUrl}/api/notifications/unread-count`,
        params('LT-06', token, 'notification_unread_count')
    );
}
