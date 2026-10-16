import { config } from './config.js';
import { userForIteration } from './test-data.js';

export function durationSeconds(value) {
    const match = /^(\d+)(ms|s|m|h)$/.exec(value);
    if (!match || Number(match[1]) <= 0) throw new Error(`Invalid positive duration: ${value}`);
    return Number(match[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[match[2]];
}

// Ramp는 선형 보간된다. 경계 예약 1개를 위한 여유까지 포함한다.
export function iterationBudget(scenario) {
    let previous = scenario.startRate;
    let total = 0;
    for (const stage of scenario.stages) {
        total += (previous + stage.target) / 2 * durationSeconds(stage.duration);
        previous = stage.target;
    }
    return Math.ceil(total / durationSeconds(scenario.timeUnit)) + 1;
}

export function uniqueUsers(count, fields = []) {
    const users = Array.from({ length: count }, (_, i) => userForIteration(i, { unique: true }));
    for (const field of ['userId', ...fields]) {
        const seen = new Set();
        for (const user of users) {
            const value = user[field];
            if (!Number.isSafeInteger(value) || value <= 0 || seen.has(value)) {
                throw new Error(`Test data requires unique positive ${field}: ${value}`);
            }
            if (typeof user.accessToken !== 'string' || !user.accessToken) {
                throw new Error(`userId ${user.userId} is missing accessToken`);
            }
            seen.add(value);
        }
    }
    return users;
}

export function metricValues(summary, name) {
    const metric = summary.metrics?.[name];
    return metric?.values || metric || {};
}

export function artifact(name, contents) {
    const directory = __ENV.SINGLE_API_ARTIFACT_DIR || `results/${config.testRunId}`;
    return { [`${directory}/${name}`]: JSON.stringify(contents, null, 2) + '\n' };
}

export function summaryText(summary) {
    return 'Single API results (setup requests included in global HTTP totals; use api_measurement submetrics):\n'
        + JSON.stringify({
            http_reqs: metricValues(summary, 'http_reqs'),
            http_req_duration: metricValues(summary, 'http_req_duration'),
            http_req_failed: metricValues(summary, 'http_req_failed'),
            checks: metricValues(summary, 'checks'),
            dropped_iterations: metricValues(summary, 'dropped_iterations'),
        }, null, 2) + '\n';
}

// 통계 수집용 항상 통과 조건이다. 성능에 따른 중단/감속을 하지 않는다.
export function measurementThresholds() {
    return {
        'http_reqs{phase:api_measurement}': ['count>=0'],
        'http_req_duration{phase:api_measurement}': ['p(95)>=0'],
        'http_req_failed{phase:api_measurement}': ['rate>=0'],
    };
}

export const measurementParams = { tags: { phase: 'api_measurement' } };
export const preparationParams = { tags: { phase: 'api_preparation' } };
