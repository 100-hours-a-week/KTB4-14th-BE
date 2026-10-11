import { Counter, Rate, Trend } from 'k6/metrics';

// 고정 API 이름만 사용하고, 여행 ID·사용자 ID별 지표를 만들지 않는다.
const apiMetrics = {};
for (const name of ['regions', 'travel_plan_create', 'generation_status', 'itinerary_after_generation']) {
    apiMetrics[name] = {
        requests: new Counter(`p01_${name}_requests`),
        errors: new Counter(`p01_${name}_errors`),
        failed: new Rate(`p01_${name}_failed`),
        duration: new Trend(`p01_${name}_duration`, true),
        waiting: new Trend(`p01_${name}_waiting`, true),
    };
}

// 응답이 반환된 직후, 기존 check/throw 이전에 기록한다. 성능 threshold는 추가하지 않는다.
export function recordP01ApiResponse(name, response, expectedStatus) {
    const metrics = apiMetrics[name];
    if (!metrics) throw new Error(`Unknown P-01 API metric name: ${name}`);
    const failed = response.status !== expectedStatus;
    metrics.requests.add(1);
    metrics.errors.add(failed ? 1 : 0);
    metrics.failed.add(failed);
    metrics.duration.add(response.timings.duration);
    metrics.waiting.add(response.timings.waiting);
}
