import { runSseHandshakeSmoke } from './flow.js';

// SSE endpoint가 HTTP 200으로 연결되고 Backend의 초기 `connected` 이벤트를 보내는지 검증한다.
// 연결 유지 부하와 달리 명시적으로 close하므로 iteration이 정상 종료되어 summary에 status를 남긴다.
export const options = {
    scenarios: {
        sse_handshake_smoke: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '30s',
            gracefulStop: '0s',
        },
    },
};

export default runSseHandshakeSmoke;
