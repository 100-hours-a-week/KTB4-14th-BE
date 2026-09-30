import http from 'k6/http';

import { config } from './config.js';
import { authHeaders } from './auth.js';


// 모든 Backend API 요청에서 사용할 k6 요청 옵션을 생성한다.
export function params(scenario, accessToken, name, overrides = {}) {

    return {

        headers: {

            // Authorization, Content-Type,
            // X-Test-Run-Id 등 공통 Header
            ...authHeaders(accessToken),

            // 어떤 부하테스트 시나리오에서 발생한 요청인지 기록한다.
            //
            // 예:
            // LT-01 = 일정 조회
            // LT-02 = 장소 완료 처리
            // LT-05 = 생성 상태 Polling
            'X-Test-Scenario': scenario,
        },

        // k6 결과에서도 시나리오별로
        // 응답시간과 오류율을 구분할 수 있도록 Tag를 추가한다.
        tags: {
            scenario,
            name,
        },
        ...overrides,
    };
}


// GET 요청 공통 함수
//
// 사용 예:
// get('/api/travel-plans/1/itinerary', 'LT-01')
export function get(path, scenario, accessToken, name, overrides) {

    return http.get(
        `${config.baseUrl}${path}`,
        params(scenario, accessToken, name, overrides)
    );
}


// POST 요청 공통 함수
//
// JavaScript 객체로 전달받은 body를
// JSON 문자열로 변환해서 Backend로 전송한다.
//
// 사용 예:
// post('/api/travel-plans', requestBody, 'LT-04')
export function post(path, body, scenario, accessToken, name, overrides) {

    return http.post(
        `${config.baseUrl}${path}`,
        JSON.stringify(body),
        params(scenario, accessToken, name, overrides)
    );
}


// PATCH 요청 공통 함수
//
// 장소 완료 처리와 같은 API에서 사용한다.
//
// 사용 예:
// patch('/api/itinerary-items/100/completion', requestBody, 'LT-02')
export function patch(path, body, scenario, accessToken, name, overrides) {

    return http.patch(
        `${config.baseUrl}${path}`,
        JSON.stringify(body),
        params(scenario, accessToken, name, overrides)
    );
}
