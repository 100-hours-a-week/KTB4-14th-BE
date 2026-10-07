import { duration, vus } from '../../lib/profile.js';
import { runSseFlow } from './flow.js';

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

export default runSseFlow;
