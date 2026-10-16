// 로컬 합성 응답 검증 전용. HTTP 요청·Staging 접근·테스트 데이터 변경은 없다.
import { recordP01ApiResponse } from '../../scenarios/p01/metrics.js';

export const options = { vus: 1, iterations: 1 };

export default function () {
    const apis = [
        ['regions', 200],
        ['travel_plan_create', 202],
        ['generation_status', 200],
        ['itinerary_after_generation', 200],
    ];
    for (const [name, expectedStatus] of apis) {
        recordP01ApiResponse(name, {
            status: expectedStatus,
            timings: { duration: 10, waiting: 8 },
        }, expectedStatus);
        recordP01ApiResponse(name, {
            status: 503,
            timings: { duration: 30, waiting: 28 },
        }, expectedStatus);
        recordP01ApiResponse(name, {
            status: 0,
            timings: { duration: 50, waiting: 48 },
        }, expectedStatus);
    }
}
